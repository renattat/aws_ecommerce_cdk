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
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.LogGroupProps;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.sns.Topic;
import software.constructs.Construct;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class InvoicesServiceStack extends Stack {

    public InvoicesServiceStack(final Construct scope, final String id,
                             final StackProps props, InvoicesServiceProps invoicesServiceProps) {
        super(scope, id, props);

        FargateTaskDefinition fargateTaskDefinition = new FargateTaskDefinition(this, "TaskDefinition",
                FargateTaskDefinitionProps.builder()
                        .family("invoices-service")
                        .cpu(512)
                        .memoryLimitMiB(1024)
                        .build());

        fargateTaskDefinition.getTaskRole().addManagedPolicy(ManagedPolicy.fromAwsManagedPolicyName("AWSXRayWriteOnlyAccess"));

        AwsLogDriver logDriver = new AwsLogDriver(AwsLogDriverProps.builder()
                .logGroup(new LogGroup(this, "LogGroup",
                        LogGroupProps.builder()
                                .logGroupName("InvoicesService")
                                .removalPolicy(RemovalPolicy.DESTROY)
                                .retention(RetentionDays.ONE_MONTH)
                                .build()))
                .streamPrefix("InvoicesService")
                .build());

        Map<String, String> envVariables = new HashMap<>();
        envVariables.put("SERVER_PORT", "9095");
        envVariables.put("AWS_REGION", this.getRegion());
        envVariables.put("AWS_XRAY_DAEMON_ADDRESS", "0.0.0.0:2000");
        envVariables.put("AWS_XRAY_CONTEXT_MISSING", "IGNORE");
        envVariables.put("AWS_XRAY_TRACING_NAME", "invoicesservice");
        envVariables.put("LOGGING_LEVEL_ROOT", "INFO");

        fargateTaskDefinition.addContainer("InvoicesServiceContainer",
                ContainerDefinitionOptions.builder()
                        .image(ContainerImage.fromEcrRepository(invoicesServiceProps.repository(), "1.0.0"))
                        .containerName("invoicesservice")
                        .logging(logDriver)
                        .portMappings(Collections.singletonList(PortMapping.builder()
                                .containerPort(9095)
                                .protocol(Protocol.TCP)
                                .build()))
                        .environment(envVariables)
                        .cpu(384)
                        .memoryLimitMiB(896)
                        .build());

        fargateTaskDefinition.addContainer("xray", ContainerDefinitionOptions.builder()
                .image(ContainerImage.fromRegistry("public.ecr.aws/xray/aws-xray-daemon:latest"))
                .containerName("XRayInvoicesService")
                .logging(new AwsLogDriver(AwsLogDriverProps.builder()
                        .logGroup(new LogGroup(this, "XRayLogGroup",
                                LogGroupProps.builder()
                                        .logGroupName("XRayInvoicesService")
                                        .removalPolicy(RemovalPolicy.DESTROY)
                                        .retention(RetentionDays.ONE_MONTH)
                                        .build()))
                        .streamPrefix("XRayInvoicesService")
                        .build()))
                .portMappings(Collections.singletonList(PortMapping.builder()
                        .containerPort(2000)
                        .protocol(Protocol.UDP)
                        .build()))
                .cpu(128)
                .memoryLimitMiB(128)
                .build());

        ApplicationListener applicationListener = invoicesServiceProps.applicationLoadBalancer()
                .addListener("InvoicesServiceAlbListener", ApplicationListenerProps.builder()
                        .port(9095)
                        .protocol(ApplicationProtocol.HTTP)
                        .loadBalancer(invoicesServiceProps.applicationLoadBalancer())
                        .build());

        FargateService fargateService = new FargateService(this, "InvoicesService",
                FargateServiceProps.builder()
                        .serviceName("InvoicesService")
                        .cluster(invoicesServiceProps.cluster())
                        .taskDefinition(fargateTaskDefinition)
                        .desiredCount(2)
                        .assignPublicIp(true)
//                        .assignPublicIp(false)
                        .build());

        invoicesServiceProps.repository().grantPull(Objects.requireNonNull(fargateTaskDefinition.getExecutionRole()));

        fargateService.getConnections().getSecurityGroups().get(0).addIngressRule(
                Peer.ipv4(invoicesServiceProps.vpc().getVpcCidrBlock()), Port.tcp(9095));

        applicationListener.addTargets("InvoicesServiceAlbTarget",
                AddApplicationTargetsProps.builder()
                        .targetGroupName("invoicesServiceAlb")
                        .port(9095)
                        .protocol(ApplicationProtocol.HTTP)
                        .targets(Collections.singletonList(fargateService))
                        .deregistrationDelay(Duration.seconds(30))
                        .healthCheck(HealthCheck.builder()
                                .enabled(true)
                                .interval(Duration.seconds(30))
                                .timeout(Duration.seconds(10))
                                .path("/actuator/health")
                                .port("9095")
                                .build())
                        .build()
        );

        NetworkListener networkListener = invoicesServiceProps.networkLoadBalancer()
                .addListener("InvoicesServiceNlbListener", BaseNetworkListenerProps.builder()
                        .port(9095)
                        .protocol(
                                software.amazon.awscdk.services.elasticloadbalancingv2.Protocol.TCP
                        )
                        .build());

        networkListener.addTargets("InvoicesServiceNlbListener",
                AddNetworkTargetsProps.builder()
                        .port(9095)
                        .protocol(software.amazon.awscdk.services.elasticloadbalancingv2.Protocol.TCP)
                        .targetGroupName("invoicesServiceNlb")
                        .targets(Collections.singletonList(
                                fargateService.loadBalancerTarget(LoadBalancerTargetOptions.builder()
                                        .containerName("invoicesservice")
                                        .containerPort(9095)
                                        .protocol(Protocol.TCP)
                                        .build())
                        ))
                        .build()
        );

    }
}


record InvoicesServiceProps(
        Vpc vpc,
        Cluster cluster,
        NetworkLoadBalancer networkLoadBalancer,
        ApplicationLoadBalancer applicationLoadBalancer,
        Repository repository
) {
}