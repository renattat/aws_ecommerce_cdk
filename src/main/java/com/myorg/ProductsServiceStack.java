package com.myorg;

import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.ecs.Protocol;
import software.amazon.awscdk.services.elasticloadbalancingv2.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.HealthCheck;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.LogGroupProps;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.constructs.Construct;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class ProductsServiceStack extends Stack {

    public ProductsServiceStack(final Construct scope, final String id,
                                final StackProps props, ProductsServiceProps productsServiceProps) {
        super(scope, id, props);

        FargateTaskDefinition fargateTaskDefinition = new FargateTaskDefinition(this,
                                                        "TaskDefinition",
                FargateTaskDefinitionProps.builder()
                        .family("products-service")
                        .cpu(512)
                        .memoryLimitMiB(1024)
                        .build());
        AwsLogDriver logDriver = new AwsLogDriver(AwsLogDriverProps.builder()
                .logGroup(new LogGroup(this, "LogGroup",
                        LogGroupProps.builder()
                                .logGroupName("ProductsService")
                                .removalPolicy(RemovalPolicy.DESTROY)
                                .retention(RetentionDays.ONE_MONTH)
                                .build()))
                .streamPrefix("ProductsService")
                .build());

        Map<String, String> envVariables = new HashMap<>();
        envVariables.put("SERVER_PORT", "9090");
        fargateTaskDefinition.addContainer("ProductsServiceContainer",
                ContainerDefinitionOptions.builder()
                        .image(ContainerImage.fromEcrRepository(productsServiceProps.repository(), "1.0.0"))
                        .containerName("productsservice")
                        .logging(logDriver)
                        .portMappings(Collections.singletonList(PortMapping.builder()
                                        .containerPort(9090)
                                        .protocol(Protocol.TCP)
                                .build()))
                        .environment(envVariables)
                        .build());

        ApplicationListener applicationListener = productsServiceProps.applicationLoadBalancer()
                .addListener("ProductsServiceAlbListener", ApplicationListenerProps.builder()
                        .port(9090)
                        .protocol(ApplicationProtocol.HTTP)
                        .loadBalancer(productsServiceProps.applicationLoadBalancer())
                        .build());

        FargateService fargateService = new FargateService(this, "ProductsService",
                FargateServiceProps.builder()
                        .serviceName("ProductsService")
                        .cluster(productsServiceProps.cluster())
                        .taskDefinition(fargateTaskDefinition)
                        .desiredCount(2)
                        .assignPublicIp(true)
                        .build());
        productsServiceProps.repository().grantPull(Objects.requireNonNull(fargateTaskDefinition.getExecutionRole()));
        fargateService.getConnections().getSecurityGroups().get(0).addIngressRule(Peer.anyIpv4(), Port.tcp(9090));


        applicationListener.addTargets("ProductsServiceAlbTarget",
                AddApplicationTargetsProps.builder()
                        .targetGroupName("productsServiceAlb")
                        .port(9090)
                        .protocol(ApplicationProtocol.HTTP)
                        .targets(Collections.singletonList(fargateService))
                        .deregistrationDelay(Duration.seconds(30))
                        .healthCheck(HealthCheck.builder()
                                .enabled(true)
                                .interval(Duration.seconds(30))
                                .timeout(Duration.seconds(10))
                                .path("/actuator/health")
                                .port("9090")
                                .build())
                        .build()
                );

        NetworkListener networkListener = productsServiceProps.networkLoadBalancer()
                .addListener("ProductsServiceNlbListener", BaseNetworkListenerProps.builder()
                        .port(9090)
                        .protocol(software.amazon.awscdk.services.elasticloadbalancingv2.Protocol.TCP)
                        .build());
        networkListener.addTargets("ProductsServiceNlbTarget",
                AddNetworkTargetsProps.builder()
                        .port(9090)
                        .protocol(software.amazon.awscdk.services.elasticloadbalancingv2.Protocol.TCP)
                        .targetGroupName("productServiceNlb")
                        .targets(Collections.singletonList(
                                fargateService.loadBalancerTarget(LoadBalancerTargetOptions.builder()
                                        .containerName("productsservice")
                                        .containerPort(9090)
                                        .protocol(Protocol.TCP)
                                        .build()
                        )))
                        .build()
                );


    }
}

record






ProductsServiceProps(
        Vpc vpc,
        Cluster cluster,
        NetworkLoadBalancer networkLoadBalancer,
        ApplicationLoadBalancer applicationLoadBalancer,
        Repository repository
){}
