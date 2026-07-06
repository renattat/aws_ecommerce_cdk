package com.myorg;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.apigateway.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.NetworkLoadBalancer;
import software.constructs.Construct;

import java.util.HashMap;
import java.util.Map;

// Api_Gateway
public class ApiStack extends Stack {

    public ApiStack(final Construct scope, final String id,
                    final StackProps props, ApiStackProps apiStackProps) {
        super(scope, id, props);

        RestApi restApi = new RestApi(this, "RestApi",
                RestApiProps.builder()
                        .restApiName("EcommerceAPI")
                        .build()
        );
        this.createProductsResource(restApi, apiStackProps);
    }

    private void createProductsResource(RestApi restApi, ApiStackProps apiStackProps) {
        // /products
        Resource productsResource = restApi.getRoot().addResource("products");

        // GET /products
        productsResource.addMethod("GET", new Integration(
                IntegrationProps.builder()
                        .type(IntegrationType.HTTP_PROXY)
                        .integrationHttpMethod("GET")
                        .uri("http://" + apiStackProps.networkLoadBalancer().getLoadBalancerDnsName() +
                                ":8080/api/products")
                        .options(IntegrationOptions.builder()
                                .vpcLink(apiStackProps.vpcLink())
                                .connectionType(ConnectionType.VPC_LINK)
                                .build())
                        .build()
        ));

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
                                .build())
                        .build()
        ));

        // PUT /products/{id}
        Map<String, String> productIdIntegrationParameters = new HashMap<>();
        productIdIntegrationParameters.put("integration.request.path.id", "method.request.path.id");

        Map<String, Boolean> productIdMethodParameters = new HashMap<>();
        productIdMethodParameters.put("method.request.path.id", true);

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
                        .build()), MethodOptions.builder()
                                .requestParameters(productIdMethodParameters)
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
    }
}

record ApiStackProps(
        NetworkLoadBalancer networkLoadBalancer,
        VpcLink vpcLink) {
}
