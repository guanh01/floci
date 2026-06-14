package io.github.hectorvent.floci.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.rds.RdsService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.kms.KmsService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Admin endpoint for seeding state into a running floci instance.
 * Accepts POST /_admin/seed/{service} with a JSON payload containing
 * resources to inject. This enables lazy-fetch proxies to seed state
 * on demand without restarting the emulator.
 */
@Path("/_admin/seed")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SeedController {

    private static final Logger LOG = Logger.getLogger(SeedController.class);

    private final DynamoDbService dynamoDbService;
    private final Ec2Service ec2Service;
    private final IamService iamService;
    private final RdsService rdsService;
    private final S3Service s3Service;
    private final SnsService snsService;
    private final KmsService kmsService;
    private final ObjectMapper objectMapper;

    @Inject
    public SeedController(DynamoDbService dynamoDbService,
                          Ec2Service ec2Service,
                          IamService iamService,
                          RdsService rdsService,
                          S3Service s3Service,
                          SnsService snsService,
                          KmsService kmsService,
                          ObjectMapper objectMapper) {
        this.dynamoDbService = dynamoDbService;
        this.ec2Service = ec2Service;
        this.iamService = iamService;
        this.rdsService = rdsService;
        this.s3Service = s3Service;
        this.snsService = snsService;
        this.kmsService = kmsService;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/{service}")
    public Response seed(@PathParam("service") String service, JsonNode body) {
        LOG.infov("Seeding service: {0}", service);
        try {
            int count = switch (service) {
                case "dynamodb" -> seedDynamoDb(body);
                case "ec2" -> seedEc2(body);
                case "iam" -> seedIam(body);
                case "rds" -> seedRds(body);
                case "s3" -> seedS3(body);
                case "sns" -> seedSns(body);
                case "kms" -> seedKms(body);
                default -> {
                    LOG.warnv("Unsupported seed service: {0}", service);
                    yield -1;
                }
            };

            if (count < 0) {
                return Response.status(400)
                        .entity(Map.of("error", "Unsupported service: " + service))
                        .build();
            }

            return Response.ok(Map.of("service", service, "seeded", count)).build();
        } catch (Exception e) {
            LOG.errorv(e, "Failed to seed service {0}", service);
            return Response.status(500)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    // ─── DynamoDB ──────────────────────────────────────────────────────────────

    private int seedDynamoDb(JsonNode body) {
        String region = body.path("region").asText("us-east-1");
        JsonNode tables = body.path("tables");
        if (!tables.isArray()) {
            throw new IllegalArgumentException("Expected 'tables' array in DynamoDB seed payload");
        }

        int count = 0;
        for (JsonNode tableNode : tables) {
            String tableName = tableNode.path("tableName").asText(tableNode.path("name").asText(null));
            if (tableName == null) {
                LOG.warn("Skipping table entry without tableName/name");
                continue;
            }

            List<KeySchemaElement> keySchema = parseKeySchema(tableNode.path("keySchema"));
            List<AttributeDefinition> attrDefs = parseAttributeDefinitions(tableNode.path("attributeDefinitions"));

            if (keySchema.isEmpty() || attrDefs.isEmpty()) {
                LOG.warnv("Skipping table {0}: missing keySchema or attributeDefinitions", tableName);
                continue;
            }

            long readCapacity = tableNode.path("readCapacityUnits").asLong(5);
            long writeCapacity = tableNode.path("writeCapacityUnits").asLong(5);

            TableDefinition table = dynamoDbService.createTable(
                    tableName, keySchema, attrDefs,
                    readCapacity, writeCapacity, region);

            if (tableNode.path("billingMode").asText(null) != null) {
                table.setBillingMode(tableNode.path("billingMode").asText());
            }
            if (tableNode.has("tags") && tableNode.get("tags").isObject()) {
                tableNode.get("tags").fields().forEachRemaining(entry ->
                        table.getTags().put(entry.getKey(), entry.getValue().asText()));
            }
            if (tableNode.path("pointInTimeRecoveryEnabled").asBoolean(false)) {
                table.setPointInTimeRecoveryEnabled(true);
            }
            if (tableNode.path("deletionProtectionEnabled").asBoolean(false)) {
                table.setDeletionProtectionEnabled(true);
            }
            dynamoDbService.persistTable(tableName, table, region);

            JsonNode items = tableNode.path("items");
            if (items.isArray()) {
                for (JsonNode item : items) {
                    dynamoDbService.putItem(tableName, item, region);
                }
            }

            count++;
            LOG.infov("Seeded DynamoDB table: {0} in {1}", tableName, region);
        }
        return count;
    }

    // ─── EC2 ───────────────────────────────────────────────────────────────────

    private int seedEc2(JsonNode body) {
        String region = body.path("region").asText("us-east-1");
        int count = 0;

        JsonNode instances = body.path("instances");
        if (instances.isArray()) {
            for (JsonNode node : instances) {
                Instance instance = objectMapper.convertValue(node, Instance.class);
                if (instance.getInstanceId() == null) continue;
                if (instance.getRegion() == null) instance.setRegion(region);
                if (instance.getState() == null) instance.setState(InstanceState.running());
                ec2Service.seedInstance(region, instance);
                count++;
            }
        }

        JsonNode securityGroups = body.path("securityGroups");
        if (securityGroups.isMissingNode()) securityGroups = body.path("security_groups");
        if (securityGroups.isArray()) {
            for (JsonNode node : securityGroups) {
                SecurityGroup sg = objectMapper.convertValue(node, SecurityGroup.class);
                if (sg.getGroupId() == null) continue;
                ec2Service.seedSecurityGroup(region, sg);
                count++;
            }
        }

        JsonNode vpcs = body.path("vpcs");
        if (vpcs.isArray()) {
            for (JsonNode node : vpcs) {
                Vpc vpc = objectMapper.convertValue(node, Vpc.class);
                if (vpc.getVpcId() == null) continue;
                ec2Service.seedVpc(region, vpc);
                count++;
            }
        }

        JsonNode volumes = body.path("volumes");
        if (volumes.isArray()) {
            for (JsonNode node : volumes) {
                Volume vol = objectMapper.convertValue(node, Volume.class);
                if (vol.getVolumeId() == null) continue;
                ec2Service.seedVolume(region, vol);
                count++;
            }
        }

        return count;
    }

    // ─── IAM ───────────────────────────────────────────────────────────────────

    private int seedIam(JsonNode body) {
        int count = 0;

        JsonNode roles = body.path("roles");
        if (roles.isArray()) {
            for (JsonNode node : roles) {
                String roleName = node.path("role_name").asText(node.path("roleName").asText(null));
                if (roleName == null) continue;
                String path = node.path("path").asText("/");
                String assumeDoc = node.path("assume_role_policy_document")
                        .asText(node.path("assumeRolePolicyDocument").asText("{}"));
                String description = node.path("description").asText("");
                iamService.createRole(roleName, path, assumeDoc, description, 3600, null);
                count++;
            }
        }

        JsonNode instanceProfiles = body.path("instanceProfiles");
        if (instanceProfiles.isMissingNode()) instanceProfiles = body.path("instance_profiles");
        if (instanceProfiles.isArray()) {
            for (JsonNode node : instanceProfiles) {
                String profileName = node.path("instance_profile_name")
                        .asText(node.path("instanceProfileName").asText(null));
                if (profileName == null) continue;
                String path = node.path("path").asText("/");
                iamService.createInstanceProfile(profileName, path);
                JsonNode profileRoles = node.path("roles");
                if (profileRoles.isArray()) {
                    for (JsonNode r : profileRoles) {
                        iamService.addRoleToInstanceProfile(profileName, r.asText());
                    }
                }
                count++;
            }
        }

        // password_policy: not yet supported in floci IAM

        return count;
    }

    // ─── RDS ───────────────────────────────────────────────────────────────────

    private int seedRds(JsonNode body) {
        int count = 0;

        JsonNode dbInstances = body.path("dbInstances");
        if (dbInstances.isMissingNode()) dbInstances = body.path("db_instances");
        if (dbInstances.isArray()) {
            for (JsonNode node : dbInstances) {
                String id = firstNonNull(node, "dbInstanceIdentifier", "db_instance_identifier");
                if (id == null) continue;
                String engine = node.path("engine").asText("mysql");
                String engineVersion = firstNonNull(node, "engineVersion", "engine_version", "8.0.35");
                String instanceClass = firstNonNull(node, "dbInstanceClass", "db_instance_class", "db.t3.micro");
                String masterUser = firstNonNull(node, "masterUsername", "master_username", "admin");
                int allocatedStorage = node.has("allocatedStorage")
                        ? node.path("allocatedStorage").asInt(20)
                        : node.path("allocated_storage").asInt(20);
                String status = firstNonNull(node, "dbInstanceStatus", "db_instance_status", "AVAILABLE");

                rdsService.seedDbInstance(id, engine, engineVersion, instanceClass,
                        masterUser, allocatedStorage, status);
                count++;
            }
        }

        JsonNode dbClusters = body.path("dbClusters");
        if (dbClusters.isMissingNode()) dbClusters = body.path("db_clusters");
        if (dbClusters.isArray()) {
            for (JsonNode node : dbClusters) {
                String id = firstNonNull(node, "dbClusterIdentifier", "db_cluster_identifier");
                if (id == null) continue;
                String engine = node.path("engine").asText("aurora-mysql");
                String engineVersion = firstNonNull(node, "engineVersion", "engine_version", "8.0.mysql_aurora.3.04.1");
                String masterUser = firstNonNull(node, "masterUsername", "master_username", "admin");
                String status = node.path("status").asText("AVAILABLE");

                rdsService.seedDbCluster(id, engine, engineVersion, masterUser, status);
                count++;
            }
        }

        JsonNode dbSnapshots = body.path("dbSnapshots");
        if (dbSnapshots.isMissingNode()) dbSnapshots = body.path("db_snapshots");
        if (dbSnapshots.isArray()) {
            for (JsonNode node : dbSnapshots) {
                String snapshotId = firstNonNull(node, "dbSnapshotIdentifier", "db_snapshot_identifier");
                if (snapshotId == null) continue;
                String instanceId = firstNonNull(node, "dbInstanceIdentifier", "db_instance_identifier", "");
                String engine = node.path("engine").asText("mysql");
                String engineVersion = firstNonNull(node, "engineVersion", "engine_version", "8.0.35");
                String status = node.path("status").asText("available");
                int allocatedStorage = node.has("allocatedStorage")
                        ? node.path("allocatedStorage").asInt(20)
                        : node.path("allocated_storage").asInt(20);
                String masterUser = firstNonNull(node, "masterUsername", "master_username", "admin");

                rdsService.seedDbSnapshot(snapshotId, instanceId, engine, engineVersion,
                        status, allocatedStorage, masterUser);
                count++;
            }
        }

        return count;
    }

    // ─── S3 ────────────────────────────────────────────────────────────────────

    private int seedS3(JsonNode body) {
        String region = body.path("region").asText("us-east-1");
        int count = 0;

        JsonNode buckets = body.path("buckets");
        if (buckets.isArray()) {
            for (JsonNode node : buckets) {
                String name = node.path("name").asText(null);
                if (name == null) continue;
                s3Service.createBucket(name, region);
                count++;
            }
        }

        return count;
    }

    // ─── SNS ───────────────────────────────────────────────────────────────────

    private int seedSns(JsonNode body) {
        String region = body.path("region").asText("us-east-1");
        int count = 0;

        JsonNode topics = body.path("topics");
        if (topics.isArray()) {
            for (JsonNode node : topics) {
                String topicArn = node.path("topic_arn").asText(node.path("topicArn").asText(null));
                if (topicArn == null) continue;
                String topicName = topicArn.contains(":") ?
                        topicArn.substring(topicArn.lastIndexOf(':') + 1) : topicArn;
                snsService.createTopic(topicName, null, null, region);
                count++;
            }
        }

        return count;
    }

    // ─── KMS ───────────────────────────────────────────────────────────────────

    private int seedKms(JsonNode body) {
        int count = 0;

        JsonNode keys = body.path("keys");
        if (keys.isArray()) {
            for (JsonNode node : keys) {
                String description = node.path("description").asText("");
                String region = body.path("region").asText("us-east-1");
                kmsService.createKey(description, region);
                count++;
            }
        }

        return count;
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private String firstNonNull(JsonNode node, String camelKey, String snakeKey) {
        String val = node.path(camelKey).asText(null);
        if (val == null) val = node.path(snakeKey).asText(null);
        return val;
    }

    private String firstNonNull(JsonNode node, String camelKey, String snakeKey, String defaultVal) {
        String val = node.path(camelKey).asText(null);
        if (val == null) val = node.path(snakeKey).asText(null);
        return val != null ? val : defaultVal;
    }

    private List<KeySchemaElement> parseKeySchema(JsonNode node) {
        List<KeySchemaElement> result = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode ks : node) {
                result.add(new KeySchemaElement(
                        ks.path("attributeName").asText(),
                        ks.path("keyType").asText()));
            }
        }
        return result;
    }

    private List<AttributeDefinition> parseAttributeDefinitions(JsonNode node) {
        List<AttributeDefinition> result = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode ad : node) {
                result.add(new AttributeDefinition(
                        ad.path("attributeName").asText(),
                        ad.path("attributeType").asText()));
            }
        }
        return result;
    }
}
