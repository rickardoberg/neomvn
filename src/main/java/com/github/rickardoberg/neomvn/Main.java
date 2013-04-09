package com.github.rickardoberg.neomvn;

import java.io.File;
import java.io.FileFilter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.DefaultModelBuilder;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelProblemCollector;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.resolution.InvalidRepositoryException;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.apache.maven.model.validation.ModelValidator;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.DynamicRelationshipType;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.index.Index;
import org.neo4j.graphdb.index.IndexHits;
import org.neo4j.kernel.impl.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

public class Main
{
    private final GraphDatabaseService graphDatabaseService;
    private final File repository;
    private final Logger logger;
    private Index<Node> groups;
    private Index<Node> artifacts;
    private Index<Node> versions;
    private DynamicRelationshipType has_artifact;
    private final DynamicRelationshipType has_version;
    private DynamicRelationshipType has_dependency;

    private Transaction tx;
    private int count = 0;

    public static void main( String[] args ) throws ParserConfigurationException, IOException, SAXException
    {
        if (args.length == 1)
            new Main(new File(args[0]));
        else
            new Main(new File("."));
    }

    public Main(File repository) throws ParserConfigurationException, IOException, SAXException
    {
        File dbPath = new File("neomvn");
        dbPath.mkdir();

        FileUtils.deleteRecursively( dbPath );

        graphDatabaseService = new GraphDatabaseFactory().newEmbeddedDatabase( dbPath.getAbsolutePath() );
        groups = graphDatabaseService.index().forNodes( "groups" );
        artifacts = graphDatabaseService.index().forNodes( "artifacts" );
        versions = graphDatabaseService.index().forNodes( "versions" );

        has_artifact = DynamicRelationshipType.withName( "HAS_ARTIFACT" );
        has_version = DynamicRelationshipType.withName( "HAS_VERSION" );
        has_dependency = DynamicRelationshipType.withName( "HAS_DEPENDENCY" );

        logger = LoggerFactory.getLogger( getClass() );
        this.repository = repository;

        try
        {
            tx = graphDatabaseService.beginTx();

            // Add versions
            logger.info( "Versions" );
            visitPoms( repository, new Visitor<Model>()
            {
                public void accept( Model item )
                {
                    artifact( item );
                }
            });

            // Add dependencies
            logger.info( "Dependencies" );
            visitPoms( repository, new Visitor<Model>()
            {
                public void accept( Model item )
                {
                    dependencies( item );
                }
            } );

            tx.success();
            tx.finish();
        }
        finally
        {
            graphDatabaseService.shutdown();
        }
    }

    private void visitPoms( File repository, Visitor<Model> visitor) throws IOException, SAXException
    {
        if ( repository.isDirectory() )
        {
            File[] directories = repository.listFiles( new FileFilter()
            {
                public boolean accept( File pathname )
                {
                    return pathname.isDirectory();
                }
            } );

            for ( File directory : directories )
            {
                visitPoms( directory, visitor );
            }

            File[] poms = repository.listFiles( new FilenameFilter()
            {
                public boolean accept( File dir, String name )
                {
                    return name.endsWith( ".pom" );
                }
            } );

            for ( File pom : poms )
            {
                visitPom( pom, visitor );
                count++;

                if (count%1000 == 0)
                {
                    tx.success();
                    tx.finish();
                    tx=graphDatabaseService.beginTx();
                }
            }
        }
    }

    private void visitPom( File pomfile, Visitor<Model> visitor )
    {
        ModelBuildingRequest req = new DefaultModelBuildingRequest();
        req.setProcessPlugins( false );
        req.setPomFile( pomfile );
        req.setModelResolver( new RepositoryModelResolver(  ) );
        req.setValidationLevel( ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL );

        DefaultModelBuilder builder = new DefaultModelBuilderFactory().newInstance();
        builder.setModelValidator( new ModelValidator()
        {
            public void validateRawModel( Model model, ModelBuildingRequest request, ModelProblemCollector problems )
            {
            }

            public void validateEffectiveModel( Model model, ModelBuildingRequest request, ModelProblemCollector problems )
            {
                try
                {
                    Field problemsList = problems.getClass().getDeclaredField("problems");
                    problemsList.setAccessible( true );
                    List<?> list = (List<?>) problemsList.get( problems );
                    list.clear();

                    Field severitiesSet = problems.getClass().getDeclaredField("severities");
                    severitiesSet.setAccessible( true );
                    Set<?> set = (Set<?>) severitiesSet.get( problems );
                    set.clear();
                }
                catch ( Throwable e )
                {
                    logger.warn( "Could not clear problems", e );
                }
            }
        } );

        try
        {
            Model model = builder.build( req ).getEffectiveModel();

            visitor.accept( model );
        }
        catch ( Throwable e )
        {
            LoggerFactory.getLogger( getClass() ).warn( "Could not handle: " + pomfile, e );
            throw new RuntimeException( e );
        }
    }

    private void artifact( Model model )
    {
        String groupId = getGroupId( model );
        String artifactId = model.getArtifactId();
        String version = getVersion( model );
        logger.info(groupId+" "+artifactId+" "+ version );

        Node groupIdNode = groups.get( "groupId", groupId ).getSingle();
        if (groupIdNode == null)
        {
            groupIdNode = graphDatabaseService.createNode();
            groupIdNode.setProperty( "groupId", groupId );
            autoIndex( groups, groupIdNode );
        }

        Node artifactIdNode = artifacts.get( "artifactId", artifactId ).getSingle();
        if (artifactIdNode == null)
        {
            artifactIdNode = graphDatabaseService.createNode();
            artifactIdNode.setProperty( "groupId", groupId );
            artifactIdNode.setProperty( "artifactId", artifactId );
            autoIndex( artifacts, artifactIdNode );
        }

        Node versionNode = graphDatabaseService.createNode();
        versionNode.setProperty( "groupId", groupId );
        versionNode.setProperty( "artifactId", artifactId );
        versionNode.setProperty( "version", version );
        if (model.getName() != null)
        {
            artifactIdNode.setProperty( "name", model.getName() );
            versionNode.setProperty( "name", model.getName() );
        }

        autoIndex( versions, versionNode);

        if (artifactIdNode.getSingleRelationship( has_artifact, Direction.INCOMING ) == null)
        {
            groupIdNode.createRelationshipTo( artifactIdNode, has_artifact );
        }

        artifactIdNode.createRelationshipTo( versionNode, has_version );
    }

    private void autoIndex( Index<Node> versions, Node node )
    {
        for ( String property : node.getPropertyKeys() )
        {
            versions.add(node, property, node.getProperty( property ));
        }
    }

    private String getVersion( Model model )
    {
        if (model.getVersion() == null)
            return model.getParent().getVersion();
        else
            return model.getVersion();
    }

    private String getGroupId( Model model )
    {
        if (model.getGroupId() == null)
            return model.getParent().getGroupId();
        else
            return model.getGroupId();
    }

    private void dependencies( final Model model )
    {
        Transaction tx = graphDatabaseService.beginTx();
        try
        {
            visitVersion( getGroupId( model ), model.getArtifactId(), getVersion( model ), new Visitor<Node>()
            {
                public void accept( final Node versionNode )
                {
                    // Found artifact, now add dependencies
                    for ( final Dependency dependency : model.getDependencies() )
                    {
                        visitVersion( dependency.getGroupId(), dependency.getArtifactId(), getVersion( model, dependency ),
                                new Visitor<Node>()
                                {
                                    public void accept( Node dependencyVersionNode )
                                    {
                                        Relationship dependencyRel = versionNode.createRelationshipTo(
                                                dependencyVersionNode, has_dependency );

                                        dependencyRel.setProperty( "scope", withDefault(dependency.getScope(), "compile" ));
                                        dependencyRel.setProperty( "optional", withDefault(dependency.isOptional(), Boolean.FALSE ));
                                    }
                                } );
                    }
                }
            } );

            tx.success();
        } finally
        {
            tx.finish();
        }
    }

    private String getVersion( Model model, final Dependency dependency )
    {
        if (dependency.getVersion() != null)
            return dependency.getVersion();

        File parentPom = pomFor(model.getParent().getGroupId(), model.getParent().getArtifactId(), model.getParent().getVersion());

        final AtomicReference<String> version = new AtomicReference<String>(  );
        visitPom( parentPom, new Visitor<Model>()
        {
            public void accept( Model item )
            {
                for ( Dependency managedDependency : item.getDependencyManagement().getDependencies() )
                {
                    if (dependency.getGroupId().equals( managedDependency.getGroupId() ) && dependency.getArtifactId().equals( managedDependency.getArtifactId() ))
                    {
                        version.set( managedDependency.getVersion() );
                    }
                }
            }
        } );

        return version.get();
    }

    private boolean visitVersion( String groupId, String artifactId, String version, Visitor<Node> visitor )
    {
        IndexHits<Node> versionNodes = versions.get( "version", version );
        try
        {
            for ( Node versionNode : versionNodes )
            {
                if (versionNode.getProperty( "artifactId" ).equals( artifactId ) && versionNode.getProperty( "groupId" ).equals( groupId ))
                {
                    visitor.accept( versionNode );
                    return true;
                }
            }
            return false;
        }
        finally
        {
            versionNodes.close();
        }
    }

    private File pomFor(String groupId, String artifactId, String versionId)
    {
        File pom = repository;
        String[] groupIds = groupId.split( "\\." );
        for ( String id : groupIds )
        {
            pom = new File(pom, id);
        }

        pom = new File(pom, artifactId);

        pom = new File(pom, versionId );

        pom = new File(pom, artifactId+"-"+versionId+".pom");

        return pom;
    }

    interface Visitor<T>
    {
        void accept(T item);
    }

    public static <T> T withDefault(T value, T defaultValue)
    {
        return value == null ? defaultValue : value;
    }

    private class RepositoryModelResolver implements ModelResolver
    {
        public ModelSource resolveModel( String groupId, String artifactId, String versionId ) throws UnresolvableModelException
        {
            File pom = repository;
            String[] groupIds = groupId.split( "\\." );
            for ( String id : groupIds )
            {
                pom = new File(pom, id);
            }

            pom = new File(pom, artifactId);

            pom = new File(pom, versionId );

            pom = new File(pom, artifactId+"-"+versionId+".pom");

            return new FileModelSource( pom );
        }

        public void addRepository( Repository repository ) throws InvalidRepositoryException
        {
        }

        public ModelResolver newCopy()
        {
            return this;
        }
    }
}
