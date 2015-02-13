package com.github.rickardoberg.neomvn;

import java.io.File;
import java.io.FileFilter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
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

    private ModelResolver modelResolver;

    private Transaction tx;
    private int count = 0;

    private List<String> failedPoms = new ArrayList<String>(  );

    public static void main( String[] args ) throws ParserConfigurationException, IOException, SAXException
    {
        if (args.length == 1)
            new Main(new File(args[0]));
        else
            new Main(new File("."));
    }

    public Main(File repository) throws ParserConfigurationException, IOException, SAXException
    {
        modelResolver = new ModelResolver( new RepositoryModelResolver(repository, "http://repo1.maven.org/maven2") );

        File dbPath = new File("neomvn");
        dbPath.mkdir();

        FileUtils.deleteRecursively( dbPath );

        graphDatabaseService = new GraphDatabaseFactory().newEmbeddedDatabase( dbPath.getAbsolutePath() );
        try
        {
            tx = graphDatabaseService.beginTx();

            groups = graphDatabaseService.index().forNodes( "groups" );
            artifacts = graphDatabaseService.index().forNodes( "artifacts" );
            versions = graphDatabaseService.index().forNodes( "versions" );

            has_artifact = DynamicRelationshipType.withName( "HAS_ARTIFACT" );
            has_version = DynamicRelationshipType.withName( "HAS_VERSION" );
            has_dependency = DynamicRelationshipType.withName( "HAS_DEPENDENCY" );

            logger = LoggerFactory.getLogger( getClass() );
            this.repository = repository;

            // Add versions
            logger.info( "Versions" );
            visitPoms( repository, new Visitor<Model>()
                    {
                public void accept( Model model )
                {
                    String groupId = getGroupId( model );
                    String artifactId = model.getArtifactId();
                    String version = getVersion( model );
                    String name = model.getName();
                    if (name == null)
                        name = artifactId;
                    artifact( groupId, artifactId, version, name);
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
        }
        finally
        {
            graphDatabaseService.shutdown();
        }

        System.err.println( "Failed POM files" );
        for ( String failedPom : failedPoms )
        {
            System.err.println( failedPom );
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
                    tx=graphDatabaseService.beginTx();
                }
            }
        }
    }

    private void visitPom( File pomfile, Visitor<Model> visitor )
    {
        try
        {
            Model model = modelResolver.resolve( pomfile );

            visitor.accept( model );
        }
        catch ( Throwable e )
        {
            LoggerFactory.getLogger( getClass() ).warn( "Could not handle: " + pomfile, e );
            pomfile.delete();
            failedPoms.add( pomfile.getAbsolutePath() );
        }
    }

    private Node artifact( String groupId, String artifactId, String version, String name )
    {
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
        artifactIdNode.setProperty( "name", name );
        versionNode.setProperty( "name", name );

        autoIndex( versions, versionNode);

        if (artifactIdNode.getSingleRelationship( has_artifact, Direction.INCOMING ) == null)
        {
            groupIdNode.createRelationshipTo( artifactIdNode, has_artifact );
        }

        artifactIdNode.createRelationshipTo( versionNode, has_version );

        return versionNode;
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
        visitVersion( getGroupId( model ), model.getArtifactId(), getVersion( model ), new Visitor<Node>()
                {
            public void accept( final Node versionNode )
            {
                // Found artifact, now add dependencies
                for ( final Dependency dependency : model.getDependencies() )
                {
                    visitVersion( dependency.getGroupId(), dependency.getArtifactId(), getVersion( dependency ),
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
    }

    private String getVersion( final Dependency dependency )
    {
        String version = dependency.getVersion();
        if (version.startsWith( "[" ))
        {
            version = version.substring( 1, version.indexOf( "," ) );
        }
        return version;
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

            // Broken lookup - create fake node and mark as
            Node fakeNode = artifact( groupId, artifactId, version, artifactId );
            fakeNode.setProperty( "missing", true );
            visitor.accept( fakeNode );

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

}
