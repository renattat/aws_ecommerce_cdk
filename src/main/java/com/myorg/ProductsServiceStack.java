package com.myorg;

import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.dynamodb.*;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.ecs.Protocol;
import software.amazon.awscdk.services.elasticloadbalancingv2.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.HealthCheck;
import software.amazon.awscdk.services.iam.ManagedPolicy;
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

        Table productsDdb = new Table(this, "ProductsDdb",
                TableProps.builder()
                        .partitionKey(Attribute.builder()
                                .name("id")
                                .type(AttributeType.STRING)
                                .build())
                        .tableName("products")
                        .removalPolicy(RemovalPolicy.DESTROY)
                        .billingMode(BillingMode.PROVISIONED)
                        .readCapacity(1)
                        .writeCapacity(1)
                        .build());


        FargateTaskDefinition fargateTaskDefinition = new FargateTaskDefinition(this, "TaskDefinition",
                FargateTaskDefinitionProps.builder()
                        .family("products-service")
                        .cpu(512)
                        .memoryLimitMiB(1024)
                        .build());

        productsDdb.grantReadWriteData(fargateTaskDefinition.getTaskRole());

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
        envVariables.put("SERVER_PORT", "8080");
        envVariables.put("AWS_PRODUCTSDDB_NAME", productsDdb.getTableName());
        envVariables.put("AWS_REGION", this.getRegion());
        envVariables.put("AWS_XRAY_DAEMON_ADDRESS", "0.0.0.0:2000");
        envVariables.put("AWS_XRAY_CONTEXT_MISSING", "IGNORE");
        envVariables.put("AWS_XRAY_TRACING_NAME", "productsservice");


        fargateTaskDefinition.addContainer("ProductsServiceContainer",
                ContainerDefinitionOptions.builder()
                        .image(ContainerImage.fromEcrRepository(productsServiceProps.repository(), "1.3.0"))
                        .containerName("productsservice")
                        .logging(logDriver)
                        .portMappings(Collections.singletonList(PortMapping.builder()
                                .containerPort(8080)
                                .protocol(Protocol.TCP)
                                .build()))
                        .environment(envVariables)
                        .cpu(384)
                        .memoryLimitMiB(896)
                        .build());

        fargateTaskDefinition.addContainer("xray", ContainerDefinitionOptions.builder()
                .image(ContainerImage.fromRegistry("public.ecr.aws/xray/aws-xray-daemon:latest"))
                .containerName("XRayProductsService")
                .logging(new AwsLogDriver(AwsLogDriverProps.builder()
                        .logGroup(new LogGroup(this, "XRayLogGroup",
                                LogGroupProps.builder()
                                        .logGroupName("XRayProductsService")
                                        .removalPolicy(RemovalPolicy.DESTROY)
                                        .retention(RetentionDays.ONE_MONTH)
                                        .build()))
                        .streamPrefix("XRayProductsService")
                        .build()))
                .portMappings(Collections.singletonList(PortMapping.builder()
                        .containerPort(2000)
                        .protocol(Protocol.UDP)
                        .build()))
                        .cpu(128)
                        .memoryLimitMiB(128)
                .build());
        fargateTaskDefinition.getTaskRole().addManagedPolicy(ManagedPolicy.fromAwsManagedPolicyName("AWSXRayWriteOnlyAccess"));

        ApplicationListener applicationListener = productsServiceProps.applicationLoadBalancer()
                .addListener("ProductsServiceAlbListener", ApplicationListenerProps.builder()
                        .port(8080)
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
//                        .assignPublicIp(false)
                        .build());
        productsServiceProps.repository().grantPull(Objects.requireNonNull(fargateTaskDefinition.getExecutionRole()));
        fargateService.getConnections().getSecurityGroups().get(0).addIngressRule(Peer.anyIpv4(), Port.tcp(8080));

        applicationListener.addTargets("ProductsServiceAlbTarget",
                AddApplicationTargetsProps.builder()
                        .targetGroupName("productsServiceAlb")
                        .port(8080)
                        .protocol(ApplicationProtocol.HTTP)
                        .targets(Collections.singletonList(fargateService))
                        .deregistrationDelay(Duration.seconds(30))
                        .healthCheck(HealthCheck.builder()
                                .enabled(true)
                                .interval(Duration.seconds(30))
                                .timeout(Duration.seconds(10))
                                .path("/actuator/health")
                                .port("8080")
                                .build())
                        .build()
        );

        NetworkListener networkListener = productsServiceProps.networkLoadBalancer()
                .addListener("ProductsServiceNlbListener", BaseNetworkListenerProps.builder()
                        .port(8080)
                        .protocol(
                                software.amazon.awscdk.services.elasticloadbalancingv2.Protocol.TCP
                        )
                        .build());

        networkListener.addTargets("ProductsServiceNlbTarget",
                AddNetworkTargetsProps.builder()
                        .port(8080)
                        .protocol(software.amazon.awscdk.services.elasticloadbalancingv2.Protocol.TCP)
                        .targetGroupName("productsServiceNlb")
                        .targets(Collections.singletonList(
                                fargateService.loadBalancerTarget(LoadBalancerTargetOptions.builder()
                                        .containerName("productsservice")
                                        .containerPort(8080)
                                        .protocol(Protocol.TCP)
                                        .build())
                        ))
                        .build()
        );
    }
}

record ProductsServiceProps(
        Vpc vpc,
        Cluster cluster,
        NetworkLoadBalancer networkLoadBalancer,
        ApplicationLoadBalancer applicationLoadBalancer,
        Repository repository
){}
