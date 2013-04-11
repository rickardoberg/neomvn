package com.github.rickardoberg.neomvn;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import org.apache.maven.model.Model;
import org.apache.maven.model.building.DefaultModelBuilder;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelProblemCollector;
import org.apache.maven.model.validation.ModelValidator;

public class ModelResolver
{
    private RepositoryModelResolver resolver;

    public ModelResolver( RepositoryModelResolver resolver)
    {
        this.resolver = resolver;
    }

    public Model resolve(File pomFile)
    {
        ModelBuildingRequest req = new DefaultModelBuildingRequest();
        req.setProcessPlugins( false );
        req.setPomFile( pomFile );
        req.setModelResolver( resolver );
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
                    e.printStackTrace();
                }
            }
        } );

        try
        {
            Model model = builder.build( req ).getEffectiveModel();
            return model;
        }
        catch ( Throwable e )
        {
            throw new RuntimeException( e );
        }

    }
}
