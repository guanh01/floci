package io.github.hectorvent.floci.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.GlobalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.LocalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.ProvisionedThroughput;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.GroupIdentifier;
import io.github.hectorvent.floci.services.ec2.model.Image;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceNetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.IpRange;
import io.github.hectorvent.floci.services.ec2.model.Ipv6Range;
import io.github.hectorvent.floci.services.ec2.model.Placement;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.UserIdGroupPair;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.VolumeAttachment;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.rds.RdsService;
import io.github.hectorvent.floci.services.sns.SnsService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin endpoint for seeding raw AWS API output directly into floci.
 * Accepts POST /_admin/seed_raw/{service} with a JSON payload that matches
 * the raw output format of AWS Describe/Get APIs (as returned by boto3 fetchers).
 *
 * This is used by CloudRail's FallbackProxy to inject real AWS state into the
 * emulator using the exact resource identifiers (ARNs, IDs) from the real account.
 *
 * Payload always includes:
 *   - ResourceType: CFN resource type (e.g., "AWS::SNS::Topic")
 *   - Region: AWS region
 *   - ... service-specific fields matching the fetcher output format
 */
@Path("/_admin/seed_raw")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SeedRawController {

    private static final Logger LOG = Logger.getLogger(SeedRawController.class);

    private final DynamoDbService dynamoDbService;
    private final SnsService snsService;
    private final KmsService kmsService;
    private final Ec2Service ec2Service;
    private final RdsService rdsService;
    private final ObjectMapper objectMapper;

    @Inject
    public SeedRawController(DynamoDbService dynamoDbService,
                             SnsService snsService,
                             KmsService kmsService,
                             Ec2Service ec2Service,
                             RdsService rdsService,
                             ObjectMapper objectMapper) {
        this.dynamoDbService = dynamoDbService;
        this.snsService = snsService;
        this.kmsService = kmsService;
        this.ec2Service = ec2Service;
        this.rdsService = rdsService;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/{service}")
    public Response seedRaw(@PathParam("service") String service, JsonNode body) {
        LOG.infov("seed_raw: service={0}, ResourceType={1}",
                service, body.path("ResourceType").asText("?"));
        try {
            String resourceType = body.path("ResourceType").asText("");
            String region = body.path("Region").asText("us-east-1");

            int count = switch (service) {
                case "dynamodb" -> seedRawDynamodb(body, resourceType, region);
                case "sns" -> seedRawSns(body, resourceType, region);
                case "kms" -> seedRawKms(body, resourceType, region);
                case "ec2" -> seedRawEc2(body, resourceType, region);
                case "rds" -> seedRawRds(body, resourceType, region);
                default -> {
                    LOG.warnv("seed_raw: unsupported service: {0}", service);
                    yield -1;
                }
            };

            if (count < 0) {
                return Response.status(400)
                        .entity(Map.of("error", "Unsupported service for seed_raw: " + service))
                        .build();
            }

            return Response.ok(Map.of("service", service, "resourceType", resourceType, "seeded", count)).build();
        } catch (Exception e) {
            LOG.errorv(e, "seed_raw failed for service {0}", service);
            return Response.status(500)
                    .entity(Map.of("error", e.getMessage() != null ? e.getMessage() : "Internal error"))
                    .build();
        }
    }

    // ─── DynamoDB ──────────────────────────────────────────────────────────────
    // Input format (from fetch_table_with_items):
    // {
    //   "ResourceType": "AWS::DynamoDB::Table",
    //   "Region": "us-east-1",
    //   "Table": {
    //     "TableName": "HotelReservations",
    //     "TableStatus": "ACTIVE",
    //     "KeySchema": [{"AttributeName": "id", "KeyType": "HASH"}],
    //     "AttributeDefinitions": [{"AttributeName": "id", "AttributeType": "S"}],
    //     "ProvisionedThroughput": {"ReadCapacityUnits": 5, "WriteCapacityUnits": 5},
    //     "TableArn": "arn:aws:dynamodb:us-east-1:123456789012:table/HotelReservations",
    //     "BillingModeSummary": {"BillingMode": "PAY_PER_REQUEST"},
    //     "GlobalSecondaryIndexes": [...],
    //     "LocalSecondaryIndexes": [...],
    //     "SSEDescription": {"Status": "ENABLED", "SSEType": "KMS", "KMSMasterKeyArn": "..."},
    //     "DeletionProtectionEnabled": false,
    //     ...
    //   },
    //   "Items": [],
    //   "ItemCount": 12
    // }

    private int seedRawDynamodb(JsonNode body, String resourceType, String region) {
        if (!"AWS::DynamoDB::Table".equals(resourceType)) {
            LOG.warnv("seed_raw/dynamodb: unsupported ResourceType: {0}", resourceType);
            return 0;
        }

        JsonNode tableNode = body.path("Table");
        if (tableNode.isMissingNode() || !tableNode.isObject()) {
            LOG.warn("seed_raw/dynamodb: missing Table object in payload");
            return 0;
        }

        String tableName = tableNode.path("TableName").asText(null);
        if (tableName == null || tableName.isEmpty()) {
            LOG.warn("seed_raw/dynamodb: missing TableName in Table object");
            return 0;
        }

        // Parse KeySchema
        List<KeySchemaElement> keySchema = new ArrayList<>();
        JsonNode ksNode = tableNode.path("KeySchema");
        if (ksNode.isArray()) {
            for (JsonNode ks : ksNode) {
                keySchema.add(new KeySchemaElement(
                        ks.path("AttributeName").asText(),
                        ks.path("KeyType").asText()));
            }
        }

        // Parse AttributeDefinitions
        List<AttributeDefinition> attrDefs = new ArrayList<>();
        JsonNode adNode = tableNode.path("AttributeDefinitions");
        if (adNode.isArray()) {
            for (JsonNode ad : adNode) {
                attrDefs.add(new AttributeDefinition(
                        ad.path("AttributeName").asText(),
                        ad.path("AttributeType").asText()));
            }
        }

        // Build TableDefinition directly (bypass createTable validation)
        TableDefinition table = new TableDefinition();
        table.setTableName(tableName);
        table.setKeySchema(keySchema);
        table.setAttributeDefinitions(attrDefs);
        table.setTableStatus(tableNode.path("TableStatus").asText("ACTIVE"));

        // TableArn — use from payload or let seedTable use default
        String tableArn = tableNode.path("TableArn").asText(null);
        if (tableArn != null && !tableArn.isEmpty()) {
            table.setTableArn(tableArn);
        }

        // ProvisionedThroughput
        JsonNode ptNode = tableNode.path("ProvisionedThroughput");
        if (ptNode.isObject()) {
            ProvisionedThroughput pt = new ProvisionedThroughput(
                    ptNode.path("ReadCapacityUnits").asLong(5),
                    ptNode.path("WriteCapacityUnits").asLong(5));
            table.setProvisionedThroughput(pt);
        }

        // BillingMode
        JsonNode billNode = tableNode.path("BillingModeSummary");
        if (billNode.isObject()) {
            table.setBillingMode(billNode.path("BillingMode").asText("PROVISIONED"));
        }

        // ItemCount
        long itemCount = body.path("ItemCount").asLong(
                tableNode.path("ItemCount").asLong(0));
        table.setItemCount(itemCount);

        // TableSizeBytes
        table.setTableSizeBytes(tableNode.path("TableSizeBytes").asLong(0));

        // CreationDateTime
        String creationStr = tableNode.path("CreationDateTime").asText(null);
        if (creationStr != null && !creationStr.isEmpty()) {
            try {
                // boto3 returns epoch seconds as a float
                double epoch = Double.parseDouble(creationStr);
                table.setCreationDateTime(Instant.ofEpochSecond((long) epoch));
            } catch (NumberFormatException e) {
                try {
                    table.setCreationDateTime(Instant.parse(creationStr));
                } catch (Exception e2) {
                    table.setCreationDateTime(Instant.now());
                }
            }
        }

        // DeletionProtectionEnabled
        table.setDeletionProtectionEnabled(
                tableNode.path("DeletionProtectionEnabled").asBoolean(false));

        // SSEDescription
        JsonNode sseNode = tableNode.path("SSEDescription");
        if (sseNode.isObject()) {
            String sseStatus = sseNode.path("Status").asText("");
            table.setSseEnabled("ENABLED".equalsIgnoreCase(sseStatus)
                    || "ENABLING".equalsIgnoreCase(sseStatus));
            table.setSseType(sseNode.path("SSEType").asText(null));
            table.setKmsMasterKeyArn(sseNode.path("KMSMasterKeyArn").asText(null));
        }

        // GlobalSecondaryIndexes
        JsonNode gsiNode = tableNode.path("GlobalSecondaryIndexes");
        if (gsiNode.isArray()) {
            List<GlobalSecondaryIndex> gsis = new ArrayList<>();
            for (JsonNode g : gsiNode) {
                List<KeySchemaElement> gsiKs = new ArrayList<>();
                JsonNode gsiKsNode = g.path("KeySchema");
                if (gsiKsNode.isArray()) {
                    for (JsonNode k : gsiKsNode) {
                        gsiKs.add(new KeySchemaElement(
                                k.path("AttributeName").asText(),
                                k.path("KeyType").asText()));
                    }
                }
                String projType = g.path("Projection").path("ProjectionType").asText("ALL");
                List<String> nonKeyAttrs = new ArrayList<>();
                JsonNode nkaNode = g.path("Projection").path("NonKeyAttributes");
                if (nkaNode.isArray()) {
                    for (JsonNode n : nkaNode) {
                        nonKeyAttrs.add(n.asText());
                    }
                }
                GlobalSecondaryIndex gsi = new GlobalSecondaryIndex(
                        g.path("IndexName").asText(),
                        gsiKs,
                        g.path("IndexArn").asText(null),
                        projType,
                        nonKeyAttrs);
                // Preserve provisioned throughput from seed
                JsonNode gsiPt = g.path("ProvisionedThroughput");
                if (gsiPt.isObject()) {
                    gsi.setProvisionedThroughput(new ProvisionedThroughput(
                            gsiPt.path("ReadCapacityUnits").asLong(0),
                            gsiPt.path("WriteCapacityUnits").asLong(0)));
                }
                gsis.add(gsi);
            }
            table.setGlobalSecondaryIndexes(gsis);
        }

        // LocalSecondaryIndexes
        JsonNode lsiNode = tableNode.path("LocalSecondaryIndexes");
        if (lsiNode.isArray()) {
            List<LocalSecondaryIndex> lsis = new ArrayList<>();
            for (JsonNode l : lsiNode) {
                List<KeySchemaElement> lsiKs = new ArrayList<>();
                JsonNode lsiKsNode = l.path("KeySchema");
                if (lsiKsNode.isArray()) {
                    for (JsonNode k : lsiKsNode) {
                        lsiKs.add(new KeySchemaElement(
                                k.path("AttributeName").asText(),
                                k.path("KeyType").asText()));
                    }
                }
                String projType = l.path("Projection").path("ProjectionType").asText("ALL");
                LocalSecondaryIndex lsi = new LocalSecondaryIndex();
                lsi.setIndexName(l.path("IndexName").asText());
                lsi.setKeySchema(lsiKs);
                lsi.setProjectionType(projType);
                lsi.setIndexArn(l.path("IndexArn").asText(null));
                lsis.add(lsi);
            }
            table.setLocalSecondaryIndexes(lsis);
        }

        // StreamSpecification
        JsonNode streamNode = tableNode.path("StreamSpecification");
        if (streamNode.isObject()) {
            table.setStreamEnabled(streamNode.path("StreamEnabled").asBoolean(false));
            table.setStreamViewType(streamNode.path("StreamViewType").asText(null));
        }
        String streamArn = tableNode.path("LatestStreamArn").asText(null);
        if (streamArn != null && !streamArn.isEmpty()) {
            table.setStreamArn(streamArn);
        }

        dynamoDbService.seedTable(region, table);
        return 1;
    }

    // ─── SNS ──────────────────────────────────────────────────────────────────
    // Input format (from fetch_topic):
    // {
    //   "ResourceType": "AWS::SNS::Topic",
    //   "Region": "us-east-1",
    //   "TopicArn": "arn:aws:sns:us-east-1:123456789012:my-topic",
    //   "Attributes": { "TopicArn": "...", "DisplayName": "...", ... },
    //   "Tags": [ {"Key": "env", "Value": "prod"} ]
    // }

    private int seedRawSns(JsonNode body, String resourceType, String region) {
        if (!"AWS::SNS::Topic".equals(resourceType)) {
            LOG.warnv("seed_raw/sns: unsupported ResourceType: {0}", resourceType);
            return 0;
        }

        // TopicArn can come from the top-level field or from Attributes
        String topicArn = body.path("TopicArn").asText(null);
        if (topicArn == null) {
            topicArn = body.path("Attributes").path("TopicArn").asText(null);
        }
        if (topicArn == null) {
            LOG.warn("seed_raw/sns: no TopicArn found in payload");
            return 0;
        }

        // Parse attributes
        Map<String, String> attributes = new HashMap<>();
        JsonNode attrsNode = body.path("Attributes");
        if (attrsNode.isObject()) {
            attrsNode.fields().forEachRemaining(entry ->
                    attributes.put(entry.getKey(), entry.getValue().asText("")));
        }

        // Parse tags (AWS format: [{"Key": "k", "Value": "v"}])
        Map<String, String> tags = new HashMap<>();
        JsonNode tagsNode = body.path("Tags");
        if (tagsNode.isArray()) {
            for (JsonNode tagNode : tagsNode) {
                String key = tagNode.path("Key").asText(null);
                String value = tagNode.path("Value").asText("");
                if (key != null) {
                    tags.put(key, value);
                }
            }
        }

        snsService.seedTopic(topicArn, attributes, tags, region);
        return 1;
    }

    // ─── KMS ──────────────────────────────────────────────────────────────────
    // Input format (from fetch_kms_key):
    // {
    //   "ResourceType": "AWS::KMS::Key",
    //   "Region": "us-east-1",
    //   "KeyMetadata": {
    //     "KeyId": "12345678-1234-1234-1234-123456789012",
    //     "Arn": "arn:aws:kms:us-east-1:123456789012:key/12345678-...",
    //     "Description": "...",
    //     "KeyState": "Enabled",
    //     "KeyUsage": "ENCRYPT_DECRYPT",
    //     "CustomerMasterKeySpec": "SYMMETRIC_DEFAULT",
    //     ...
    //   },
    //   "KeyRotationEnabled": true
    // }

    private int seedRawKms(JsonNode body, String resourceType, String region) {
        if (!"AWS::KMS::Key".equals(resourceType)) {
            LOG.warnv("seed_raw/kms: unsupported ResourceType: {0}", resourceType);
            return 0;
        }

        JsonNode metadata = body.path("KeyMetadata");
        if (metadata.isMissingNode() || !metadata.isObject()) {
            LOG.warn("seed_raw/kms: missing KeyMetadata in payload");
            return 0;
        }

        String keyId = metadata.path("KeyId").asText(null);
        if (keyId == null) {
            LOG.warn("seed_raw/kms: missing KeyId in KeyMetadata");
            return 0;
        }

        String arn = metadata.path("Arn").asText(null);
        String description = metadata.path("Description").asText("");
        String keyState = metadata.path("KeyState").asText("Enabled");
        String keyUsage = metadata.path("KeyUsage").asText("ENCRYPT_DECRYPT");
        String keySpec = metadata.path("CustomerMasterKeySpec").asText(
                metadata.path("KeySpec").asText("SYMMETRIC_DEFAULT"));
        boolean rotationEnabled = body.path("KeyRotationEnabled").asBoolean(false);

        kmsService.seedKey(keyId, arn, description, keyState, keyUsage, keySpec,
                rotationEnabled, region);
        return 1;
    }

    // ─── EC2 ──────────────────────────────────────────────────────────────────
    // Input format for volumes (from fetch_volume — raw boto3 DescribeVolumes):
    // {
    //   "ResourceType": "AWS::EC2::Volume",
    //   "Region": "us-east-1",
    //   "VolumeId": "vol-0123456789abcdef0",
    //   "VolumeType": "gp3",
    //   "Size": 100,
    //   "State": "available",
    //   "AvailabilityZone": "us-east-1a",
    //   "Encrypted": false,
    //   "Iops": 3000,
    //   "Throughput": 125,
    //   "SnapshotId": "",
    //   "CreateTime": "2024-01-01T00:00:00+00:00",
    //   "Tags": [{"Key": "Name", "Value": "my-vol"}],
    //   "Attachments": [...]
    // }

    private int seedRawEc2(JsonNode body, String resourceType, String region) {
        if ("AWS::EC2::Instance".equals(resourceType)) {
            return seedRawInstance(body, region);
        }
        if ("AWS::EC2::Volume".equals(resourceType)) {
            return seedRawVolume(body, region);
        }
        if ("AWS::EC2::Image".equals(resourceType)) {
            return seedRawImage(body, region);
        }
        if ("AWS::EC2::SecurityGroup".equals(resourceType)) {
            return seedRawSecurityGroup(body, region);
        }
        // For other EC2 resource types, log a warning and return 0
        LOG.warnv("seed_raw/ec2: unsupported ResourceType: {0}", resourceType);
        return 0;
    }

    // ─── EC2 Instance ─────────────────────────────────────────────────────────
    // Input format (from fetch_instance — raw boto3 DescribeInstances):
    // {
    //   "ResourceType": "AWS::EC2::Instance",
    //   "Region": "us-east-1",
    //   "Instance": {
    //     "InstanceId": "i-0123456789abcdef0",
    //     "InstanceType": "t3.micro",
    //     "State": {"Code": 80, "Name": "stopped"},
    //     "VpcId": "vpc-123", "SubnetId": "subnet-123",
    //     "PrivateIpAddress": "10.0.0.1",
    //     "CpuOptions": {"CoreCount": 1, "ThreadsPerCore": 2},
    //     "SecurityGroups": [{"GroupId": "sg-xxx", "GroupName": "my-sg"}],
    //     "NetworkInterfaces": [{...}],
    //     "Placement": {"AvailabilityZone": "us-east-1a", "AvailabilityZoneId": "use1-az2", "GroupName": "", "Tenancy": "default"},
    //     "MaintenanceOptions": {"AutoRecovery": "default", "RebootMigration": "default"},
    //     "IamInstanceProfile": {"Arn": "...", "Id": "AIPA..."},
    //     ...
    //   },
    //   "IamInstanceProfileAssociations": [...]
    // }

    private int seedRawInstance(JsonNode body, String region) {
        JsonNode instNode = body.path("Instance");
        if (instNode.isMissingNode() || !instNode.isObject()) {
            LOG.warn("seed_raw/ec2: missing Instance object in payload");
            return 0;
        }

        String instanceId = instNode.path("InstanceId").asText(null);
        if (instanceId == null || instanceId.isEmpty()) {
            LOG.warn("seed_raw/ec2: missing InstanceId for instance");
            return 0;
        }

        Instance instance = new Instance();
        instance.setInstanceId(instanceId);
        instance.setInstanceType(instNode.path("InstanceType").asText("t2.micro"));
        instance.setImageId(instNode.path("ImageId").asText("ami-00000000000000001"));
        instance.setVpcId(instNode.path("VpcId").asText(null));
        instance.setSubnetId(instNode.path("SubnetId").asText(null));
        instance.setPrivateIpAddress(instNode.path("PrivateIpAddress").asText("10.0.0.1"));
        instance.setPublicIpAddress(instNode.path("PublicIpAddress").asText(null));
        instance.setPrivateDnsName(instNode.path("PrivateDnsName").asText(null));
        instance.setPublicDnsName(instNode.path("PublicDnsName").asText(null));
        instance.setArchitecture(instNode.path("Architecture").asText("x86_64"));
        instance.setRootDeviceType(instNode.path("RootDeviceType").asText("ebs"));
        instance.setRootDeviceName(instNode.path("RootDeviceName").asText("/dev/xvda"));
        instance.setRegion(region);

        // Parse instance state
        JsonNode stateNode = instNode.path("State");
        if (stateNode.isObject()) {
            int code = stateNode.path("Code").asInt(16);
            String name = stateNode.path("Name").asText("running");
            instance.setState(new InstanceState(code, name));
        } else {
            instance.setState(InstanceState.running());
        }

        // Parse placement (full: AZ, AZId, GroupName, Tenancy)
        JsonNode placementNode = instNode.path("Placement");
        if (placementNode.isObject()) {
            Placement placement = new Placement();
            placement.setAvailabilityZone(placementNode.path("AvailabilityZone").asText(region + "a"));
            placement.setAvailabilityZoneId(placementNode.path("AvailabilityZoneId").asText(null));
            placement.setGroupName(placementNode.path("GroupName").asText(""));
            placement.setTenancy(placementNode.path("Tenancy").asText("default"));
            instance.setPlacement(placement);
        }

        // Parse CpuOptions
        JsonNode cpuNode = instNode.path("CpuOptions");
        if (cpuNode.isObject()) {
            instance.setCpuCoreCount(cpuNode.path("CoreCount").asInt(1));
            instance.setCpuThreadsPerCore(cpuNode.path("ThreadsPerCore").asInt(1));
        }

        // Parse SecurityGroups
        JsonNode sgNode = instNode.path("SecurityGroups");
        if (sgNode.isArray()) {
            List<GroupIdentifier> groups = new ArrayList<>();
            for (JsonNode g : sgNode) {
                groups.add(new GroupIdentifier(
                        g.path("GroupId").asText(null),
                        g.path("GroupName").asText(null)));
            }
            instance.setSecurityGroups(groups);
        }

        // Parse NetworkInterfaces
        JsonNode niNode = instNode.path("NetworkInterfaces");
        if (niNode.isArray()) {
            List<InstanceNetworkInterface> enis = new ArrayList<>();
            for (JsonNode eniNode : niNode) {
                InstanceNetworkInterface eni = new InstanceNetworkInterface();
                eni.setNetworkInterfaceId(eniNode.path("NetworkInterfaceId").asText(null));
                eni.setSubnetId(eniNode.path("SubnetId").asText(null));
                eni.setVpcId(eniNode.path("VpcId").asText(null));
                eni.setDescription(eniNode.path("Description").asText(null));
                eni.setOwnerId(eniNode.path("OwnerId").asText(null));
                eni.setStatus(eniNode.path("Status").asText("in-use"));
                eni.setMacAddress(eniNode.path("MacAddress").asText(null));
                eni.setPrivateIpAddress(eniNode.path("PrivateIpAddress").asText(null));
                eni.setPrivateDnsName(eniNode.path("PrivateDnsName").asText(null));
                eni.setSourceDestCheck(eniNode.path("SourceDestCheck").asBoolean(true));

                // Parse attachment
                JsonNode attachNode = eniNode.path("Attachment");
                if (attachNode.isObject()) {
                    eni.setAttachmentId(attachNode.path("AttachmentId").asText(null));
                    eni.setDeviceIndex(attachNode.path("DeviceIndex").asInt(0));
                    String attachTime = attachNode.path("AttachTime").asText(null);
                    if (attachTime != null) {
                        eni.setAttachTime(attachTime);
                    }
                }

                // Parse ENI security groups
                JsonNode eniGroups = eniNode.path("Groups");
                if (eniGroups.isArray()) {
                    List<GroupIdentifier> eniSgs = new ArrayList<>();
                    for (JsonNode g : eniGroups) {
                        eniSgs.add(new GroupIdentifier(
                                g.path("GroupId").asText(null),
                                g.path("GroupName").asText(null)));
                    }
                    eni.setGroups(eniSgs);
                }

                enis.add(eni);
            }
            instance.setNetworkInterfaces(enis);
        }

        // Parse monitoring
        JsonNode monitoringNode = instNode.path("Monitoring");
        if (monitoringNode.isObject()) {
            instance.setMonitoring(monitoringNode.path("State").asText("disabled"));
        }

        // Parse metadata options
        JsonNode metadataNode = instNode.path("MetadataOptions");
        if (metadataNode.isObject()) {
            instance.setHttpTokens(metadataNode.path("HttpTokens").asText("optional"));
            instance.setHttpPutResponseHopLimit(metadataNode.path("HttpPutResponseHopLimit").asInt(1));
            instance.setHttpEndpoint(metadataNode.path("HttpEndpoint").asText("enabled"));
        }

        // Parse MaintenanceOptions
        JsonNode maintenanceNode = instNode.path("MaintenanceOptions");
        if (maintenanceNode.isObject()) {
            instance.setMaintenanceAutoRecovery(maintenanceNode.path("AutoRecovery").asText("default"));
            String rebootMigration = maintenanceNode.path("RebootMigration").asText(null);
            if (rebootMigration != null && !rebootMigration.isEmpty()) {
                instance.setMaintenanceRebootMigration(rebootMigration);
            }
        }

        // Parse IAM instance profile (preserve Id from seed to avoid generated Id drift)
        JsonNode iamProfile = instNode.path("IamInstanceProfile");
        if (iamProfile.isObject()) {
            instance.setIamInstanceProfileArn(iamProfile.path("Arn").asText(null));
            String profileId = iamProfile.path("Id").asText(null);
            if (profileId != null && !profileId.isEmpty()) {
                instance.setIamInstanceProfileId(profileId);
            }
        }

        // Parse tags (AWS format: [{"Key": "k", "Value": "v"}])
        JsonNode tagsNode = instNode.path("Tags");
        if (tagsNode.isArray()) {
            List<Tag> tags = new ArrayList<>();
            for (JsonNode tagNode : tagsNode) {
                String key = tagNode.path("Key").asText(null);
                String value = tagNode.path("Value").asText("");
                if (key != null) {
                    Tag tag = new Tag();
                    tag.setKey(key);
                    tag.setValue(value);
                    tags.add(tag);
                }
            }
            instance.setTags(tags);
        }

        ec2Service.seedInstance(region, instance);
        return 1;
    }

    private int seedRawVolume(JsonNode body, String region) {
        String volumeId = body.path("VolumeId").asText(null);
        if (volumeId == null) {
            LOG.warn("seed_raw/ec2: missing VolumeId for volume");
            return 0;
        }

        Volume vol = new Volume();
        vol.setVolumeId(volumeId);
        vol.setVolumeType(body.path("VolumeType").asText("gp2"));
        vol.setSize(body.path("Size").asInt(0));
        vol.setState(body.path("State").asText("available"));
        vol.setAvailabilityZone(body.path("AvailabilityZone").asText(region + "a"));
        vol.setEncrypted(body.path("Encrypted").asBoolean(false));
        vol.setIops(body.path("Iops").asInt(0));
        if (body.has("Throughput") && !body.path("Throughput").isNull()) {
            vol.setThroughput(body.path("Throughput").asInt(0));
        }
        vol.setSnapshotId(body.path("SnapshotId").asText(null));
        vol.setRegion(region);

        // Parse CreateTime
        String createTimeStr = body.path("CreateTime").asText(null);
        if (createTimeStr != null && !createTimeStr.isEmpty()) {
            try {
                vol.setCreateTime(Instant.parse(createTimeStr));
            } catch (Exception e) {
                vol.setCreateTime(Instant.now());
            }
        } else {
            vol.setCreateTime(Instant.now());
        }

        // Parse Tags (AWS format: [{"Key": "k", "Value": "v"}])
        JsonNode tagsNode = body.path("Tags");
        if (tagsNode.isArray()) {
            List<Tag> tags = new ArrayList<>();
            for (JsonNode tagNode : tagsNode) {
                String key = tagNode.path("Key").asText(null);
                String value = tagNode.path("Value").asText("");
                if (key != null) {
                    Tag tag = new Tag();
                    tag.setKey(key);
                    tag.setValue(value);
                    tags.add(tag);
                }
            }
            vol.setTags(tags);
        }

        // Parse Attachments
        JsonNode attachmentsNode = body.path("Attachments");
        if (attachmentsNode.isArray()) {
            List<VolumeAttachment> attachments = new ArrayList<>();
            for (JsonNode attNode : attachmentsNode) {
                VolumeAttachment att = new VolumeAttachment();
                att.setVolumeId(volumeId);
                att.setInstanceId(attNode.path("InstanceId").asText(null));
                att.setDevice(attNode.path("Device").asText(null));
                att.setState(attNode.path("State").asText("attached"));
                attachments.add(att);
            }
            vol.setAttachments(attachments);
        }

        ec2Service.seedVolume(region, vol);
        return 1;
    }

    // ─── EC2 Image ───────────────────────────────────────────────────────────
    // Input format (from fetch_image — raw boto3 DescribeImages):
    // {
    //   "ResourceType": "AWS::EC2::Image",
    //   "Region": "us-east-1",
    //   "ImageId": "ami-0123456789abcdef0",
    //   "State": "available",
    //   "Name": "my-image",
    //   "Description": "My AMI",
    //   "Architecture": "x86_64",
    //   "OwnerId": "123456789012",
    //   "Public": false,
    //   "CreationDate": "2024-01-01T00:00:00.000Z"
    // }

    private int seedRawImage(JsonNode body, String region) {
        String imageId = body.path("ImageId").asText(null);
        if (imageId == null) {
            LOG.warn("seed_raw/ec2: missing ImageId for image");
            return 0;
        }

        Image image = new Image();
        image.setImageId(imageId);
        image.setName(body.path("Name").asText(null));
        image.setDescription(body.path("Description").asText(null));
        image.setState(body.path("State").asText("available"));
        image.setArchitecture(body.path("Architecture").asText("x86_64"));
        image.setCreationDate(body.path("CreationDate").asText(null));

        if (body.has("OwnerId") && !body.path("OwnerId").isNull()) {
            image.setOwnerId(body.path("OwnerId").asText());
        }
        if (body.has("Public")) {
            image.setPublic(body.path("Public").asBoolean(false));
        } else {
            image.setPublic(false);
        }
        if (body.has("Platform") && !body.path("Platform").isNull()) {
            image.setPlatform(body.path("Platform").asText(null));
        }
        if (body.has("RootDeviceType") && !body.path("RootDeviceType").isNull()) {
            image.setRootDeviceType(body.path("RootDeviceType").asText("ebs"));
        }
        if (body.has("RootDeviceName") && !body.path("RootDeviceName").isNull()) {
            image.setRootDeviceName(body.path("RootDeviceName").asText("/dev/xvda"));
        }
        if (body.has("VirtualizationType") && !body.path("VirtualizationType").isNull()) {
            image.setVirtualizationType(body.path("VirtualizationType").asText("hvm"));
        }
        if (body.has("Hypervisor") && !body.path("Hypervisor").isNull()) {
            image.setHypervisor(body.path("Hypervisor").asText("xen"));
        }

        ec2Service.seedImage(region, image);
        return 1;
    }

    // ─── EC2 Security Group ──────────────────────────────────────────────────
    // Input format (from fetch_security_group — raw boto3 DescribeSecurityGroups):
    // {
    //   "ResourceType": "AWS::EC2::SecurityGroup",
    //   "Region": "us-east-1",
    //   "GroupId": "sg-0123456789abcdef0",
    //   "GroupName": "my-sg",
    //   "Description": "My security group",
    //   "VpcId": "vpc-xxx",
    //   "OwnerId": "123456789012",
    //   "IpPermissions": [{
    //     "IpProtocol": "tcp",
    //     "FromPort": 443,
    //     "ToPort": 443,
    //     "IpRanges": [{"CidrIp": "0.0.0.0/0", "Description": "HTTPS"}],
    //     "Ipv6Ranges": [{"CidrIpv6": "::/0"}],
    //     "UserIdGroupPairs": [{"GroupId": "sg-other", "UserId": "123"}]
    //   }],
    //   "IpPermissionsEgress": [...],
    //   "Tags": [{"Key": "Name", "Value": "my-sg"}]
    // }

    private int seedRawSecurityGroup(JsonNode body, String region) {
        String groupId = body.path("GroupId").asText(null);
        if (groupId == null) {
            LOG.warn("seed_raw/ec2: missing GroupId for security group");
            return 0;
        }

        SecurityGroup sg = new SecurityGroup();
        sg.setGroupId(groupId);
        sg.setGroupName(body.path("GroupName").asText(""));
        sg.setDescription(body.path("Description").asText(""));
        sg.setVpcId(body.path("VpcId").asText(null));
        sg.setOwnerId(body.path("OwnerId").asText(null));
        sg.setRegion(region);

        // Parse IpPermissions (ingress rules)
        JsonNode ingressNode = body.path("IpPermissions");
        if (ingressNode.isArray()) {
            sg.setIpPermissions(parseIpPermissions(ingressNode));
        }

        // Parse IpPermissionsEgress (egress rules)
        JsonNode egressNode = body.path("IpPermissionsEgress");
        if (egressNode.isArray()) {
            sg.setIpPermissionsEgress(parseIpPermissions(egressNode));
        }

        // Parse Tags
        JsonNode tagsNode = body.path("Tags");
        if (tagsNode.isArray()) {
            List<Tag> tags = new ArrayList<>();
            for (JsonNode tagNode : tagsNode) {
                String key = tagNode.path("Key").asText(null);
                String value = tagNode.path("Value").asText("");
                if (key != null) {
                    Tag tag = new Tag();
                    tag.setKey(key);
                    tag.setValue(value);
                    tags.add(tag);
                }
            }
            sg.setTags(tags);
        }

        ec2Service.seedSecurityGroup(region, sg);
        return 1;
    }

    private List<IpPermission> parseIpPermissions(JsonNode permissionsNode) {
        List<IpPermission> permissions = new ArrayList<>();
        for (JsonNode permNode : permissionsNode) {
            IpPermission perm = new IpPermission();
            perm.setIpProtocol(permNode.path("IpProtocol").asText(null));

            if (permNode.has("FromPort") && !permNode.path("FromPort").isNull()) {
                perm.setFromPort(permNode.path("FromPort").asInt());
            }
            if (permNode.has("ToPort") && !permNode.path("ToPort").isNull()) {
                perm.setToPort(permNode.path("ToPort").asInt());
            }

            // Parse IpRanges
            JsonNode ipRangesNode = permNode.path("IpRanges");
            if (ipRangesNode.isArray()) {
                List<IpRange> ipRanges = new ArrayList<>();
                for (JsonNode rangeNode : ipRangesNode) {
                    String cidrIp = rangeNode.path("CidrIp").asText(null);
                    String description = rangeNode.path("Description").asText(null);
                    ipRanges.add(new IpRange(cidrIp, description));
                }
                perm.setIpRanges(ipRanges);
            }

            // Parse Ipv6Ranges
            JsonNode ipv6RangesNode = permNode.path("Ipv6Ranges");
            if (ipv6RangesNode.isArray()) {
                List<Ipv6Range> ipv6Ranges = new ArrayList<>();
                for (JsonNode rangeNode : ipv6RangesNode) {
                    String cidrIpv6 = rangeNode.path("CidrIpv6").asText(null);
                    ipv6Ranges.add(new Ipv6Range(cidrIpv6));
                }
                perm.setIpv6Ranges(ipv6Ranges);
            }

            // Parse UserIdGroupPairs
            JsonNode groupPairsNode = permNode.path("UserIdGroupPairs");
            if (groupPairsNode.isArray()) {
                List<UserIdGroupPair> groupPairs = new ArrayList<>();
                for (JsonNode pairNode : groupPairsNode) {
                    UserIdGroupPair pair = new UserIdGroupPair();
                    pair.setGroupId(pairNode.path("GroupId").asText(null));
                    pair.setUserId(pairNode.path("UserId").asText(null));
                    pair.setGroupName(pairNode.path("GroupName").asText(null));
                    groupPairs.add(pair);
                }
                perm.setUserIdGroupPairs(groupPairs);
            }

            permissions.add(perm);
        }
        return permissions;
    }

    // ─── RDS ──────────────────────────────────────────────────────────────────
    // Input format for DB instances (from fetch_db_instance — raw boto3 DescribeDBInstances[0]):
    // {
    //   "ResourceType": "AWS::RDS::DBInstance",
    //   "Region": "us-east-1",
    //   "DBInstanceIdentifier": "my-db",
    //   "DBInstanceStatus": "available",
    //   "Engine": "mysql",
    //   "EngineVersion": "8.0.35",
    //   "MasterUsername": "admin",
    //   "DBInstanceClass": "db.t3.micro",
    //   "AllocatedStorage": 20,
    //   ...
    // }
    //
    // Input format for DB clusters (from fetch_db_cluster — raw boto3 DescribeDBClusters[0]):
    // {
    //   "ResourceType": "AWS::RDS::DBCluster",
    //   "Region": "us-east-1",
    //   "DBClusterIdentifier": "my-cluster",
    //   "Status": "available",
    //   "Engine": "aurora-mysql",
    //   "EngineVersion": "8.0.mysql_aurora.3.04.1",
    //   "MasterUsername": "admin",
    //   ...
    // }
    //
    // Input format for DB snapshots (from fetch_db_snapshot — raw boto3 DescribeDBSnapshots[0]):
    // {
    //   "ResourceType": "AWS::RDS::DBSnapshot",
    //   "Region": "us-east-1",
    //   "DBSnapshotIdentifier": "my-snapshot",
    //   "DBInstanceIdentifier": "my-db",
    //   "Engine": "mysql",
    //   "EngineVersion": "8.0.35",
    //   "Status": "available",
    //   "AllocatedStorage": 20,
    //   "MasterUsername": "admin",
    //   ...
    // }

    private int seedRawRds(JsonNode body, String resourceType, String region) {
        if ("AWS::RDS::DBInstance".equals(resourceType)) {
            return seedRawDbInstance(body, region);
        }
        if ("AWS::RDS::DBCluster".equals(resourceType)) {
            return seedRawDbCluster(body, region);
        }
        if ("AWS::RDS::DBSnapshot".equals(resourceType)) {
            return seedRawDbSnapshot(body, region);
        }
        LOG.warnv("seed_raw/rds: unsupported ResourceType: {0}", resourceType);
        return 0;
    }

    private int seedRawDbInstance(JsonNode body, String region) {
        String id = body.path("DBInstanceIdentifier").asText(null);
        if (id == null || id.isEmpty()) {
            LOG.warn("seed_raw/rds: missing DBInstanceIdentifier in payload");
            return 0;
        }

        String engine = body.path("Engine").asText("mysql");
        String engineVersion = body.path("EngineVersion").asText(null);
        String dbInstanceClass = body.path("DBInstanceClass").asText("db.t3.micro");
        String masterUsername = body.path("MasterUsername").asText("admin");
        int allocatedStorage = body.path("AllocatedStorage").asInt(20);
        String status = body.path("DBInstanceStatus").asText("available");

        rdsService.seedDbInstance(id, engine, engineVersion, dbInstanceClass,
                masterUsername, allocatedStorage, status);
        return 1;
    }

    private int seedRawDbCluster(JsonNode body, String region) {
        String id = body.path("DBClusterIdentifier").asText(null);
        if (id == null || id.isEmpty()) {
            LOG.warn("seed_raw/rds: missing DBClusterIdentifier in payload");
            return 0;
        }

        String engine = body.path("Engine").asText("aurora-mysql");
        String engineVersion = body.path("EngineVersion").asText(null);
        String masterUsername = body.path("MasterUsername").asText("admin");
        String status = body.path("Status").asText("available");

        rdsService.seedDbCluster(id, engine, engineVersion, masterUsername, status);
        return 1;
    }

    private int seedRawDbSnapshot(JsonNode body, String region) {
        String snapshotId = body.path("DBSnapshotIdentifier").asText(null);
        if (snapshotId == null || snapshotId.isEmpty()) {
            LOG.warn("seed_raw/rds: missing DBSnapshotIdentifier in payload");
            return 0;
        }

        String instanceId = body.path("DBInstanceIdentifier").asText("");
        String engine = body.path("Engine").asText("mysql");
        String engineVersion = body.path("EngineVersion").asText(null);
        String status = body.path("Status").asText("available");
        int allocatedStorage = body.path("AllocatedStorage").asInt(20);
        String masterUsername = body.path("MasterUsername").asText("admin");

        rdsService.seedDbSnapshot(snapshotId, instanceId, engine, engineVersion,
                status, allocatedStorage, masterUsername);
        return 1;
    }
}
