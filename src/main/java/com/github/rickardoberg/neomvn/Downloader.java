package com.github.rickardoberg.neomvn;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

public class Downloader
{

    private final RepositoryModelResolver resolver;

    public static void main( String[] args ) throws IOException
    {
        String groupId = args[0];
        File repo = new File(args[1]);

        new Downloader(groupId, repo);
    }

    private String mavenRepo;
    private final String groupId;
    private final File repository;
    private final ModelResolver modelResolver;
    private int requestCount = 0;

    private List<String> failedDownloads = new ArrayList<String>(  );

    public Downloader( String groupId, File repository ) throws IOException
    {
        this.groupId = groupId;
        this.repository = repository;

        mavenRepo = "http://repo1.maven.org/maven2";

        resolver = new RepositoryModelResolver( repository, mavenRepo );
        modelResolver = new ModelResolver( resolver );


        File pomsFile = new File( repository, "poms.txt" );
        List<String> poms;
        if (pomsFile.exists())
            poms = loadPomList( pomsFile );
        else
            poms = findPoms( groupId );

        downloadPoms(poms);

        poms = convertToLocal(poms);

        while (!poms.isEmpty())
        {
            System.out.printf("Downloading dependencies for %d POMs\n", poms.size());
            poms = findDependencies(poms);
            downloadPoms( poms );
        }

        if (!failedDownloads.isEmpty())
        {
            System.err.println( "Failed to download following POMs:" );
            for ( String failedDownload : failedDownloads )
            {
                System.err.println( failedDownload );
            }
        }

        System.out.println("Done");
    }

    private List<String> convertToLocal( List<String> poms )
    {
        List<String> localFiles = new ArrayList<String>(  );
        for ( String pom : poms )
        {
            String file = pom.substring( mavenRepo.length() );
            File localRepoFile = new File( repository, file );
            localFiles.add(localRepoFile.getAbsolutePath());
        }
        return localFiles;
    }

    private List<String> loadPomList( File file ) throws IOException
    {
        BufferedReader reader = new BufferedReader( new InputStreamReader( new FileInputStream( file ) ) );
        String pom;
        List<String> poms = new ArrayList<String>(  );
        while ((pom = reader.readLine()) != null)
        {
            poms.add(pom);
        }

        return poms;
    }

    private List<String> findPoms( String groupId ) throws IOException
    {
        // Do a search on Maven.org for all artifacts for this groupId
        String baseUrl = mavenRepo;
        String[] groupIds = groupId.split( "\\." );
        for ( String id : groupIds )
        {
            baseUrl += "/"+id;
        }

        List<String> urls = new ArrayList<String>(  );
        urls.add(baseUrl);

        List<String> poms = new ArrayList<String>(  );

        while (!urls.isEmpty())
        {
            String url = urls.remove( 0 );
            Connection conn = Jsoup.connect( url );
            requestCount++;

            for ( Element element : conn.get().select( "pre a" ) )
            {
                if (!element.attr( "href" ).equals( "../" ))
                {
                    String href = element.absUrl( "href" );
                    System.out.println( href );

                    if (href.endsWith( "/" ))
                        urls.add( href );
                    else if (href.endsWith( ".pom" ))
                        poms.add(href);
                }
            }

            try
            {
                Thread.sleep(100);
            }
            catch ( InterruptedException e )
            {
                throw new RuntimeException( e );
            }
        }

        // List poms
        System.out.println("Found POMs");
        for ( String pom : poms )
        {
            System.out.println(pom);
        }

        return poms;
    }

    private void downloadPoms( List<String> poms ) throws IOException
    {
        for ( String pom : poms )
        {
            File localRepoFile;
            if (pom.startsWith( mavenRepo ))
            {
                String file = pom.substring( mavenRepo.length() );
                localRepoFile = new File( repository, file );
            } else
            {
                localRepoFile = new File(pom);
            }

            if (!localRepoFile.exists())
            {
                try
                {
                    resolver.download( localRepoFile );
                }
                catch ( IOException e )
                {
                    // Print and continue
                    e.printStackTrace();
                    failedDownloads.add(localRepoFile.getAbsolutePath());
                }
            }
        }
    }

    private List<String> findDependencies( List<String> poms )
    {
        List<String> dependencies = new ArrayList<String>(  );
        for ( String pom : poms )
        {
            File localRepoFile = new File( pom );

            try
            {
                Model model = modelResolver.resolve( localRepoFile );

                for ( Dependency dependency : model.getDependencies() )
                {
                    String version = dependency.getVersion();
                    if ( version.startsWith( "[" )) // Handle ranges
                        version = version.substring( 1, version.indexOf( "," )-1 );

                    File dependencyPom = resolver.getLocalFile( dependency.getGroupId(), dependency.getArtifactId(), version );

                    String dependencyPomAbsolutePath = dependencyPom.getAbsolutePath();
                    if (!dependencyPom.exists() && !dependencies.contains( dependencyPomAbsolutePath ) && !failedDownloads.contains( dependencyPomAbsolutePath ))
                        dependencies.add( dependencyPomAbsolutePath );

                  //  System.out.println(dependency+"(need download="+!dependencyPom.exists()+")");
                }
            }
            catch ( Exception e )
            {
                failedDownloads.add(pom);
                System.err.println("Could not resolve dependencies for:"+pom);
            }
        }

        return dependencies;
    }
}
