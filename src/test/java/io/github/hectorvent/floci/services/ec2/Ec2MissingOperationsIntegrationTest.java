package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Integration tests for EC2 operations required by CloudRail runbooks:
 * - CreateImage
 * - CreateSnapshot / DescribeSnapshots / DeleteSnapshot
 * - EnableEbsEncryptionByDefault / DisableEbsEncryptionByDefault / GetEbsEncryptionByDefault
 * - ModifyInstanceMetadataOptions
 * - MonitorInstances / UnmonitorInstances
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2MissingOperationsIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/ec2/aws4_request";

    private static String instanceId;
    private static String volumeId;
    private static String snapshotId;
    private static String imageId;

    // ── Setup: create instance and volume ─────────────────────────────────────

    @Test
    @Order(1)
    void setupInstance() {
        instanceId = given()
                .header("Authorization", AUTH_HEADER)
                .formParam("Action", "RunInstances")
                .formParam("ImageId", "ami-0abcdef1234567890")
                .formParam("InstanceType", "t2.micro")
                .formParam("MinCount", "1")
                .formParam("MaxCount", "1")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("RunInstancesResponse.instancesSet.item.instanceId", startsWith("i-"))
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
    }

    @Test
    @Order(2)
    void setupVolume() {
        volumeId = given()
                .header("Authorization", AUTH_HEADER)
                .formParam("Action", "CreateVolume")
                .formParam("AvailabilityZone", "us-east-1a")
                .formParam("VolumeType", "gp2")
                .formParam("Size", "10")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("CreateVolumeResponse.volumeId", startsWith("vol-"))
                .extract().path("CreateVolumeResponse.volumeId");
    }

    // ── CreateImage ───────────────────────────────────────────────────────────

    @Test
    @Order(10)
    void createImageReturnsAmiId() {
        imageId = given()
                .header("Authorization", AUTH_HEADER)
                .formParam("Action", "CreateImage")
                .formParam("InstanceId", instanceId)
                .formParam("Name", "test-image")
                .formParam("Description", "Test image for CloudRail")
                .formParam("NoReboot", "true")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("CreateImageResponse.imageId", startsWith("ami-"))
                .extract().path("CreateImageResponse.imageId");
    }

    @Test
    @Order(11)
    void describeImagesIncludesCreatedImage() {
        given()
                .header("Authorization", AUTH_HEADER)
                .formParam("Action", "DescribeImages")
                .formParam("ImageId.1", imageId)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeImagesResponse.imagesSet.item.imageId", equalTo(imageId))
                .body("DescribeImagesResponse.imagesSet.item.name", equalTo("test-image"));
    }

    // ── CreateSnapshot ────────────────────────────────────────────────────────

    @Test
    @Order(20)
    void createSnapshotReturnsSnapId() {
        snapshotId = given()
                .header("Authorization", AUTH_HEADER)
                .formParam("Action", "CreateSnapshot")
                .formParam("VolumeId", volumeId)
                .formParam("Description", "Test snapshot")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("CreateSnapshotResponse.snapshotId", startsWith("snap-"))
                .body("CreateSnapshotResponse.volumeId", equalTo(volumeId))
                .body("CreateSnapshotResponse.status", equalTo("completed"))
                .body("CreateSnapshotResponse.volumeSize", equalTo("10"))
                .extract().path("CreateSnapshotResponse.snapshotId");
    }

    @Test
    @Order(21)
    void describeSnapshotsReturnsCreatedSnapshot() {
        given()
                .header("Authorization", AUTH_HEADER)
                .formParam("Action", "DescribeSnapshots")
                .formParam("SnapshotId.1", snapshotId)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeSnapshotsResponse.snapshotSet.item.snapshotId", equalTo(snapshotId))
                .body("DescribeSnapshotsResponse.snapshotSet.item.volumeId", equalTo(volumeId));
    }

    @Test
    @Order(22)
    void deleteSnapshotSucceeds() {
        given()
                .header("Authorization", AUTH_HEADER)
                .formParam("Action", "DeleteSnapshot")
                .formParam("SnapshotId", snapshotId)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DeleteSnapshotResponse.return", equalTo("true"));
    }

    // ── EnableEbsEncryptionByDefault ──────────────────────────────────────────

    @Test
    @Order(30)
    void enableEbsEncryptionByDefault() {
        given()
                .header("Authorization", AUTH_HEADER)
                .formParam("Action", "EnableEbsEncryptionByDefault")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("EnableEbsEncryptionByDefaultResponse.ebsEncryptionByDefault", equalTo("true"));
    }

    @Test
    @Order(31)
    void getEbsEncryptionByDefaultReturnsTrue() {
        given()
                .header("Authorization", AUTH_HEADER)
                .formParam("Action", "GetEbsEncryptionByDefault")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("GetEbsEncryptionByDefaultResponse.ebsEncryptionByDefault", equalTo("true"));
    }

    @Test
    @Order(32)
    void disableEbsEncryptionByDefault() {
        given()
                .header("Authorization", AUTH_HEADER)
                .formParam("Action", "DisableEbsEncryptionByDefault")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DisableEbsEncryptionByDefaultResponse.ebsEncryptionByDefault", equalTo("false"));
    }

    // ── ModifyInstanceMetadataOptions ─────────────────────────────────────────

    @Test
    @Order(40)
    void modifyInstanceMetadataOptionsUpdatesFields() {
        given()
                .header("Authorization", AUTH_HEADER)
                .formParam("Action", "ModifyInstanceMetadataOptions")
                .formParam("InstanceId", instanceId)
                .formParam("HttpTokens", "required")
                .formParam("HttpPutResponseHopLimit", "2")
                .formParam("HttpEndpoint", "enabled")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("ModifyInstanceMetadataOptionsResponse.instanceId", equalTo(instanceId))
                .body("ModifyInstanceMetadataOptionsResponse.instanceMetadataOptions.httpTokens", equalTo("required"))
                .body("ModifyInstanceMetadataOptionsResponse.instanceMetadataOptions.httpPutResponseHopLimit", equalTo("2"))
                .body("ModifyInstanceMetadataOptionsResponse.instanceMetadataOptions.httpEndpoint", equalTo("enabled"));
    }

    @Test
    @Order(41)
    void describeInstancesReflectsMetadataChange() {
        given()
                .header("Authorization", AUTH_HEADER)
                .formParam("Action", "DescribeInstances")
                .formParam("InstanceId.1", instanceId)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.metadataOptions.httpTokens",
                        equalTo("required"))
                .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.metadataOptions.httpPutResponseHopLimit",
                        equalTo("2"));
    }

    // ── MonitorInstances / UnmonitorInstances ─────────────────────────────────

    @Test
    @Order(50)
    void monitorInstancesEnablesMonitoring() {
        given()
                .header("Authorization", AUTH_HEADER)
                .formParam("Action", "MonitorInstances")
                .formParam("InstanceId.1", instanceId)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("MonitorInstancesResponse.instancesSet.item.instanceId", equalTo(instanceId))
                .body("MonitorInstancesResponse.instancesSet.item.monitoring.state", equalTo("enabled"));
    }

    @Test
    @Order(51)
    void describeInstancesReflectsMonitoringEnabled() {
        given()
                .header("Authorization", AUTH_HEADER)
                .formParam("Action", "DescribeInstances")
                .formParam("InstanceId.1", instanceId)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.monitoring.state",
                        equalTo("enabled"));
    }

    @Test
    @Order(52)
    void unmonitorInstancesDisablesMonitoring() {
        given()
                .header("Authorization", AUTH_HEADER)
                .formParam("Action", "UnmonitorInstances")
                .formParam("InstanceId.1", instanceId)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("UnmonitorInstancesResponse.instancesSet.item.instanceId", equalTo(instanceId))
                .body("UnmonitorInstancesResponse.instancesSet.item.monitoring.state", equalTo("disabled"));
    }
}
