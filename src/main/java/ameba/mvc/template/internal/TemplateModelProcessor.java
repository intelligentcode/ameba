package ameba.mvc.template.internal;


import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.internal.Errors;
import org.glassfish.jersey.internal.util.Producer;
import org.glassfish.jersey.internal.util.ReflectionHelper;
import org.glassfish.jersey.message.internal.MediaTypes;
import org.glassfish.jersey.process.Inflector;
import org.glassfish.jersey.server.ExtendedUriInfo;
import org.glassfish.jersey.server.internal.inject.ConfiguredValidator;
import org.glassfish.jersey.server.model.*;
import org.glassfish.jersey.server.model.internal.ModelHelper;
import org.glassfish.jersey.server.model.internal.ModelProcessorUtil;
import org.glassfish.jersey.server.mvc.Template;
import org.glassfish.jersey.server.mvc.Viewable;
import org.glassfish.jersey.server.mvc.internal.LocalizationMessages;
import org.glassfish.jersey.server.mvc.internal.TemplateInflector;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.ws.rs.Consumes;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.Produces;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ResourceContext;
import javax.ws.rs.core.Configuration;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.security.AccessController;
import java.util.List;

/**
 * {@link ModelProcessor Model processor} enhancing (sub-)resources with {@value HttpMethod#GET} methods responsible of producing
 * implicit {@link org.glassfish.jersey.server.mvc.Viewable viewables}.
 * <p/>
 * Note: Resource classes has to be annotated with {@link Template} annotation in order to be enhanced by this model processor.
 *
 * @author Michal Gajdos (michal.gajdos at oracle.com)
 * @author icode
 * @see Template
 */
class TemplateModelProcessor implements ModelProcessor {

    /**
     * Path parameter representing implicit template name.
     */
    private static final String IMPLICIT_VIEW_PATH_PARAMETER = "implicit-view-path-parameter";
    private static final String IMPLICIT_VIEW_PATH_PARAMETER_TEMPLATE = "{" + IMPLICIT_VIEW_PATH_PARAMETER + "}";


    private final ResourceContext resourceContext;

    private final Provider<ExtendedUriInfo> extendedUriInfoProvider;
    private final Provider<ConfiguredValidator> validatorProvider;

    /**
     * Create a {@code TemplateModelProcessor} instance.
     *
     * @param resourceContext         (injected) resource context.
     * @param extendedUriInfoProvider (injected) extended uri info provider.
     */
    @Inject
    TemplateModelProcessor(final ResourceContext resourceContext,
                           final Provider<ConfiguredValidator> validatorProvider,
                           final Provider<ExtendedUriInfo> extendedUriInfoProvider) {
        this.resourceContext = resourceContext;
        this.validatorProvider = validatorProvider;
        this.extendedUriInfoProvider = extendedUriInfoProvider;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ResourceModel processResourceModel(final ResourceModel resourceModel, final Configuration configuration) {
        return processModel(resourceModel, false);
    }

    /** {@inheritDoc} */
    @Override
    public ResourceModel processSubResource(final ResourceModel subResourceModel, final Configuration configuration) {
        return processModel(subResourceModel, true);
    }

    /**
     * Enhance {@link org.glassfish.jersey.server.model.RuntimeResource runtime resources} of given
     * {@link org.glassfish.jersey.server.model.ResourceModel resource model} with methods obtained with
     * {@link #getEnhancingMethods(org.glassfish.jersey.server.model.RuntimeResource)}.
     *
     * @param resourceModel    resource model to enhance runtime resources of.
     * @param subResourceModel determines whether the resource model represents sub-resource.
     * @return enhanced resource model.
     */
    private ResourceModel processModel(final ResourceModel resourceModel, final boolean subResourceModel) {
        ResourceModel.Builder newModelBuilder = processTemplateAnnotatedInvocables(resourceModel, subResourceModel);

        for (RuntimeResource resource : resourceModel.getRuntimeResourceModel().getRuntimeResources()) {
            ModelProcessorUtil.enhanceResource(resource, newModelBuilder, getEnhancingMethods(resource), false);
        }

        return newModelBuilder.build();
    }

    /**
     * Process all {@link Invocable invocables} and defines
     * {@link org.glassfish.jersey.server.model.Invocable#getRoutingResponseType() routing response types}
     * as {@link Viewable} for all methods annotated with {@link Template}.
     *
     * @param resourceModel    resource model to process.
     * @param subResourceModel determines whether the resource model represents sub-resource.
     * @return Modified resource model.
     */
    private ResourceModel.Builder processTemplateAnnotatedInvocables(ResourceModel resourceModel,
                                                                     final boolean subResourceModel) {
        ResourceModel.Builder modelBuilder = new ResourceModel.Builder(subResourceModel);
        for (Resource resource : resourceModel.getResources()) {
            Resource newResource = processResource(resource);
            modelBuilder.addResource(newResource);
        }
        return modelBuilder;
    }

    private Resource processResource(final Resource resource) {
        Resource.Builder resourceBuilder = Resource.builder(resource.getPath());
        for (final ResourceMethod resourceMethod : resource.getResourceMethods()) {
            ResourceMethod.Builder builder = resourceBuilder.addMethod(resourceMethod);
            if (resourceMethod.getInvocable().getHandlingMethod().isAnnotationPresent(Template.class)) {
                builder.routingResponseType(Viewable.class);
            }
        }
        if (resource.getResourceLocator() != null) {
            resourceBuilder.addMethod(resource.getResourceLocator());
        }

        for (Resource child : resource.getChildResources()) {
            resourceBuilder.addChildResource(processResource(child));
        }
        return resourceBuilder.build();

    }

    /**
     * Returns a list of enhancing methods for a given {@link org.glassfish.jersey.server.model.RuntimeResource runtime resource}.
     *
     * @param runtimeResource runtime resource to create enhancing methods for.
     * @return list of enhancing methods.
     */
    private List<ModelProcessorUtil.Method> getEnhancingMethods(final RuntimeResource runtimeResource) {
        final List<ModelProcessorUtil.Method> newMethods = Lists.newArrayList();

        for (final Resource resource : runtimeResource.getResources()) {
            // Handler classes.
            for (final Class<?> handlerClass : resource.getHandlerClasses()) {
                createEnhancingMethods(handlerClass, null, newMethods);
            }

            // Names - if there are no handler classes / instances.
            if (resource.getHandlerClasses().isEmpty() && resource.getHandlerInstances().isEmpty()) {
                for (String resourceName : resource.getNames()) {
                    final Class<Object> resourceClass = AccessController
                            .doPrivileged(ReflectionHelper.classForNamePA(resourceName));
                    if (resourceClass != null) {
                        createEnhancingMethods(resourceClass, null, newMethods);
                    }
                }
            }

            // Handler instances.
            Errors.process((Producer<Void>) () -> {
                for (final Object handlerInstance : resource.getHandlerInstances()) {
                    final Class<?> handlerInstanceClass = handlerInstance.getClass();

                    if (!resource.getHandlerClasses().contains(handlerInstanceClass)) {
                        createEnhancingMethods(handlerInstanceClass, handlerInstance, newMethods);
                    } else {
                        Errors.warning(resource,
                                LocalizationMessages.TEMPLATE_HANDLER_ALREADY_ENHANCED(handlerInstanceClass));
                    }
                }

                return null;
            });
        }

        return newMethods;
    }

    /**
     * Creates enhancing methods for given resource.
     *
     * @param resourceClass    resource class for which enhancing methods should be created.
     * @param resourceInstance resource instance for which enhancing methods should be created. May be {@code null}.
     * @param newMethods       list to store new methods into.
     */
    private void createEnhancingMethods(final Class<?> resourceClass, final Object resourceInstance,
                                        final List<ModelProcessorUtil.Method> newMethods) {
        final Template template = resourceClass.getAnnotation(Template.class);

        if (template != null) {
            final Class<?> annotatedResourceClass = ModelHelper.getAnnotatedResourceClass(resourceClass);

            final List<MediaType> produces = MediaTypes
                    .createQualitySourceMediaTypes(annotatedResourceClass.getAnnotation(Produces.class));
            final List<MediaType> consumes = MediaTypes.createFrom(annotatedResourceClass.getAnnotation(Consumes.class));

            final TemplateInflectorImpl inflector = new TemplateInflectorImpl(template.name(),
                    resourceClass, resourceInstance);

            newMethods.add(new ModelProcessorUtil.Method(HttpMethod.GET, consumes, produces, inflector));
            newMethods.add(new ModelProcessorUtil.Method(IMPLICIT_VIEW_PATH_PARAMETER_TEMPLATE, HttpMethod.GET,
                    consumes, produces, inflector));
        }
    }

    /**
     * Inflector producing response with {@link org.glassfish.jersey.server.mvc.spi.ResolvedViewable resolved viewable} where
     * model is the resource class annotated with {@link Template} or 404 as its status code.
     */
    private class TemplateInflectorImpl implements TemplateInflector, Inflector<ContainerRequestContext, Response> {

        private final String[] templateName;

        private final Class<?> resourceClass;
        private final Object resourceInstance;

        private Class<?> modelClass;

        /**
         * Create enhancing template {@link Inflector inflector} method.
         *
         * @param templateName     template name for the produced {@link org.glassfish.jersey.server.mvc.Viewable viewable}.
         * @param resourceClass    model class for the produced {@link org.glassfish.jersey.server.mvc.Viewable viewable}.
         *                         Should not be {@code null}.
         * @param resourceInstance model for the produced {@link org.glassfish.jersey.server.mvc.Viewable viewable}. May be
         *                         {@code null}.
         */
        private TemplateInflectorImpl(final String templateName, final Class<?> resourceClass,
                                      final Object resourceInstance) {
            this.templateName = new String[]{templateName};

            this.resourceClass = resourceClass;
            this.resourceInstance = resourceInstance;
        }


        private TemplateInflectorImpl(final String[] templateName, final Class<?> resourceClass,
                                      final Object resourceInstance) {
            this.templateName = templateName;

            this.resourceClass = resourceClass;
            this.resourceInstance = resourceInstance;
        }

        @Override
        public Response apply(ContainerRequestContext requestContext) {
            final List<String> templateNames = getTemplateNames(requestContext);
            final Object model = getModel(extendedUriInfoProvider.get());

            // Validate resource class.
            final ConfiguredValidator validator = validatorProvider.get();
            if (validator != null) {
                validator.validateResourceAndInputParams(model, null, null);
            }

            return Response.ok().entity(new ImplicitViewable(templateNames, model, resourceClass)).build();
        }

        @Override
        public Class<?> getModelClass() {
            return modelClass;
        }

        private Object setModelClass(final Object model) {
            if (modelClass == null) {
                modelClass = model.getClass();
            }
            return model;
        }

        /**
         * Obtains a model object for a viewable.
         *
         * @param extendedUriInfo uri info to obtain last matched resource from.
         * @return a model object.
         */
        private Object getModel(final ExtendedUriInfo extendedUriInfo) {
            final List<Object> matchedResources = extendedUriInfo.getMatchedResources();

            if (resourceInstance != null) {
                return setModelClass(resourceInstance);
            } else if (matchedResources.size() > 1) {
                return setModelClass(matchedResources.get(1));
            } else {
                return setModelClass(resourceContext.getResource(resourceClass));
            }
        }

        /**
         * Returns a list of template names to be considered as candidates for resolving
         * {@link org.glassfish.jersey.server.mvc.Viewable viewable} into
         * {@link org.glassfish.jersey.server.mvc.spi.ResolvedViewable resolved viewable}.
         * <p/>
         * Order of template names to be resolved is as follows:
         * <ul>
         * <li>{{@value #IMPLICIT_VIEW_PATH_PARAMETER}} value</li>
         * <li>{@link org.glassfish.jersey.server.mvc.Template#name()}</li>
         * <li>last sub-resource manager path</li>
         * <li>index</li>
         * </ul>
         *
         * @param requestContext request context to obtain {@link #IMPLICIT_VIEW_PATH_PARAMETER} value from.
         * @return a non-empty list of template names.
         */
        private List<String> getTemplateNames(final ContainerRequestContext requestContext) {
            final List<String> templateNames = Lists.newArrayList();

            // Template name extracted from path param.
            final String pathTemplate = requestContext.getUriInfo().getPathParameters().getFirst(IMPLICIT_VIEW_PATH_PARAMETER);
            if (pathTemplate != null) {
                templateNames.add(pathTemplate);
            }

            // Annotation.
            if (this.templateName != null && this.templateName.length > 0) {
                for (String name : this.templateName) {
                    if (StringUtils.isNotBlank(name)) {
                        templateNames.add(name);
                    }
                }
            }

            // Sub-resource path.
            final ExtendedUriInfo uriInfo = extendedUriInfoProvider.get();
            final List<RuntimeResource> matchedRuntimeResources = uriInfo.getMatchedRuntimeResources();
            if (matchedRuntimeResources.size() > 1) {
                // > 1 to check that we matched sub-resource
                final RuntimeResource lastMatchedRuntimeResource = matchedRuntimeResources.get(0);
                final Resource lastMatchedResource = lastMatchedRuntimeResource.getResources().get(0);

                String path = lastMatchedResource.getPath();

                if (path != null && !IMPLICIT_VIEW_PATH_PARAMETER_TEMPLATE.equals(path)) {
                    path = path.charAt(0) == '/' ? path.substring(1, path.length()) : path;
                    templateNames.add(path);
                }
            }

            // Index.
            if (templateNames.isEmpty()) {
                templateNames.add("index");
            }

            return templateNames;
        }
    }
}
