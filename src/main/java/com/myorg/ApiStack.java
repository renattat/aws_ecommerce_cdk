package com.myorg;

import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.apigateway.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.NetworkLoadBalancer;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.LogGroupProps;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.constructs.Construct;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

// Api_Gateway
public class ApiStack extends Stack {

    public ApiStack(final Construct scope, final String id,
                    final StackProps props, ApiStackProps apiStackProps) {
        super(scope, id, props);

        LogGroup logGroup = new LogGroup(this, "ECommerceApiLogs", LogGroupProps.builder()
                .logGroupName("ECommerceApi")
                .removalPolicy(RemovalPolicy.DESTROY)
                .retention(RetentionDays.ONE_MONTH)
                .build());

        RestApi restApi = new RestApi(this, "RestApi",
                RestApiProps.builder()
                        .restApiName("ECommerceApi")
                        .cloudWatchRole(true)
                        .deployOptions(StageOptions.builder()
                                .loggingLevel(MethodLoggingLevel.INFO)
                                .accessLogDestination(new LogGroupLogDestination(logGroup))
                                .accessLogFormat(
                                        AccessLogFormat.jsonWithStandardFields(
                                                JsonWithStandardFieldProps.builder()
                                                        .caller(true)
                                                        .httpMethod(true)
                                                        .ip(true)
                                                        .protocol(true)
                                                        .requestTime(true)
                                                        .resourcePath(true)
                                                        .responseLength(true)
                                                        .status(true)
                                                        .user(true)
                                                        .build()
                                        )
                                )
                                .build())
                        .build()
        );

        Resource productResource = this.createProductsResource(restApi, apiStackProps);

        this.createProductEventsResource(restApi, apiStackProps, productResource);
    }

    private void createProductEventsResource(RestApi restApi, ApiStackProps apiStackProps, Resource productsResource) {

        // /products/events
        Resource productEventsResource = productsResource.addResource("events");

        Map<String, String> productEventsIntegrationParameters = new HashMap<>();
        productEventsIntegrationParameters.put("integration.request.header.requestId", "context.requestId");

        Map<String, Boolean> productEventsMethodParameters = new HashMap<>();
        productEventsMethodParameters.put("method.request.header.requestId", false);
        productEventsMethodParameters.put("method.request.querystring.eventType", true);
        productEventsMethodParameters.put("method.request.querystring.limit", false);
        productEventsMethodParameters.put("method.request.querystring.from", false);
        productEventsMethodParameters.put("method.request.querystring.to", false);
        productEventsMethodParameters.put("method.request.querystring.exclusiveStartTimestamp", false);

        //GET /products/events?eventType=PRODUCT_CRATED&limit=10&from=1&to5&exclusiveStartTimestamp=123
        productEventsResource.addMethod("GET", new Integration(
                IntegrationProps.builder()
                        .type(IntegrationType.HTTP_PROXY)
                        .integrationHttpMethod("GET")
                        .uri("http://" + apiStackProps.networkLoadBalancer().getLoadBalancerDnsName() +
                                ":9090/api/products/events")
                        .options(IntegrationOptions.builder()
                                .vpcLink(apiStackProps.vpcLink())
                                .connectionType(ConnectionType.VPC_LINK)
                                .requestParameters(productEventsIntegrationParameters)
                                .build())
                        .build()
        ), MethodOptions.builder()
                        .requestValidator(new RequestValidator(this, "ProductEventsValidator", RequestValidatorProps.builder()
                                .restApi(restApi)
                                .requestValidatorName("ProductEventsValidator")
                                .validateRequestParameters(true)
                                .build()))
                .requestParameters(productEventsMethodParameters)
                .build());
    }

    private Resource createProductsResource(RestApi restApi, ApiStackProps apiStackProps) {
        Map<String, String> productsIntegrationParameters = new HashMap<>();
        productsIntegrationParameters.put("integration.request.header.requestId", "context.requestId");

        Map<String, Boolean> productsMethodParameters = new HashMap<>();
        productsMethodParameters.put("method.request.header.requestId", false);
        productsMethodParameters.put("method.request.querystring.code", false);

        // /products
        Resource productsResource = restApi.getRoot().addResource("products");

        // GET /products
        // GET /products?code=CODE1
        productsResource.addMethod("GET", new Integration(
                IntegrationProps.builder()
                        .type(IntegrationType.HTTP_PROXY)
                        .integrationHttpMethod("GET")
                        .uri("http://" + apiStackProps.networkLoadBalancer().getLoadBalancerDnsName() +
                                ":8080/api/products")
                        .options(IntegrationOptions.builder()
                                .vpcLink(apiStackProps.vpcLink())
                                .connectionType(ConnectionType.VPC_LINK)
                                .requestParameters(productsIntegrationParameters)
                                .build())
                        .build()
        ), MethodOptions.builder()
                .requestParameters(productsMethodParameters)
                .build());

        RequestValidator porductRequestValidator = new RequestValidator(this, "ProductRequestValidator",
                RequestValidatorProps.builder()
                        .restApi(restApi)
                        .requestValidatorName("Product request validator")
                        .validateRequestBody(true)
                        .build()
        );

        Map<String, JsonSchema> productModelProperties = new HashMap<>();
        productModelProperties.put("name", JsonSchema.builder()
                .type(JsonSchemaType.STRING)
                .minLength(5)
                .maxLength(50)
                .build());
        productModelProperties.put("code", JsonSchema.builder()
                .type(JsonSchemaType.STRING)
                .minLength(5)
                .maxLength(15)
                .build());
        productModelProperties.put("model", JsonSchema.builder()
                .type(JsonSchemaType.STRING)
                .minLength(5)
                .maxLength(50)
                .build());
        productModelProperties.put("price", JsonSchema.builder()
                .type(JsonSchemaType.NUMBER)
                .minimum(10.0)
                .maximum(1000.0)
                .build());

        Model producModel = new Model(this, "ProductModel",
                ModelProps.builder()
                        .modelName("ProductName")
                        .restApi(restApi)
                        .contentType("application/json")
                        .schema(JsonSchema.builder()
                                .type(JsonSchemaType.OBJECT)
                                .properties(productModelProperties)
                                .required(Arrays.asList("name", "code"))
                                .build())
                        .build());

        Map<String, Model> productRequestModels = new HashMap<>();
        productRequestModels.put("application/json", producModel);

        // POST /products
        productsResource.addMethod("POST", new Integration(
                IntegrationProps.builder()
                        .type(IntegrationType.HTTP_PROXY)
                        .integrationHttpMethod("POST")
                        .uri("http://" + apiStackProps.networkLoadBalancer().getLoadBalancerDnsName() +
                                ":8080/api/products")
                        .options(IntegrationOptions.builder()
                                .vpcLink(apiStackProps.vpcLink())
                                .connectionType(ConnectionType.VPC_LINK)
                                .requestParameters(productsIntegrationParameters)
                                .build())
                        .build()
        ), MethodOptions.builder()
                .requestParameters(productsMethodParameters)
                .requestValidator(porductRequestValidator)
                .requestModels(productRequestModels)
                .build());

        // PUT /products/{id}
        Map<String, String> productIdIntegrationParameters = new HashMap<>();
        productIdIntegrationParameters.put("integration.request.path.id", "method.request.path.id");
        productIdIntegrationParameters.put("integration.request.header.requestId", "context.requestId");

        Map<String, Boolean> productIdMethodParameters = new HashMap<>();
        productIdMethodParameters.put("method.request.path.id", true);
        productIdMethodParameters.put("method.request.header.requestId", false);


        Resource productIdResource = productsResource.addResource("{id}");
        productIdResource.addMethod("PUT", new Integration(
                        IntegrationProps.builder()
                                .type(IntegrationType.HTTP_PROXY)
                                .integrationHttpMethod("PUT")
                                .uri("http://" + apiStackProps.networkLoadBalancer().getLoadBalancerDnsName() +
                                        ":8080/api/products/{id}")
                                .options(IntegrationOptions.builder()
                                        .vpcLink(apiStackProps.vpcLink())
                                        .connectionType(ConnectionType.VPC_LINK)
                                        .requestParameters(productIdIntegrationParameters)
                                        .build())
                                .build()),
                MethodOptions.builder()
                        .requestParameters(productIdMethodParameters)
                        .requestValidator(porductRequestValidator)
                        .requestModels(productRequestModels)
                        .build());


        // GET /products/{id}
        productIdResource.addMethod("GET", new Integration(
                IntegrationProps.builder()
                        .type(IntegrationType.HTTP_PROXY)
                        .integrationHttpMethod("GET")
                        .uri("http://" + apiStackProps.networkLoadBalancer().getLoadBalancerDnsName() +
                                ":8080/api/products/{id}")
                        .options(IntegrationOptions.builder()
                                .vpcLink(apiStackProps.vpcLink())
                                .connectionType(ConnectionType.VPC_LINK)
                                .requestParameters(productIdIntegrationParameters)
                                .build())
                        .build()), MethodOptions.builder()
                .requestParameters(productIdMethodParameters)
                .build());

        // DELETE /products{id}
        productIdResource.addMethod("DELETE", new Integration(
                IntegrationProps.builder()
                        .type(IntegrationType.HTTP_PROXY)
                        .integrationHttpMethod("DELETE")
                        .uri("http://" + apiStackProps.networkLoadBalancer().getLoadBalancerDnsName() +
                                ":8080/api/products/{id}")
                        .options(IntegrationOptions.builder()
                                .vpcLink(apiStackProps.vpcLink())
                                .connectionType(ConnectionType.VPC_LINK)
                                .requestParameters(productIdIntegrationParameters)
                                .build())
                        .build()), MethodOptions.builder()
                .requestParameters(productIdMethodParameters)
                .build());

        return productsResource;
    }
}

record ApiStackProps(
        NetworkLoadBalancer networkLoadBalancer,
        VpcLink vpcLink) {
}
