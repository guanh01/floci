package io.github.hectorvent.floci.services.ec2.model;

import java.time.Instant;

public class FlowLog {
    private String flowLogId;
    private String resourceId;
    private String resourceType;
    private String trafficType;
    private String logDestinationType;
    private String logDestination;
    private String logGroupName;
    private String deliverLogsStatus;
    private String flowLogStatus;
    private String creationTime;

    public FlowLog(String flowLogId, String resourceId, String resourceType,
                   String trafficType, String logDestinationType,
                   String logDestination, String logGroupName) {
        this.flowLogId = flowLogId;
        this.resourceId = resourceId;
        this.resourceType = resourceType;
        this.trafficType = trafficType;
        this.logDestinationType = logDestinationType;
        this.logDestination = logDestination;
        this.logGroupName = logGroupName;
        this.deliverLogsStatus = "SUCCESS";
        this.flowLogStatus = "ACTIVE";
        this.creationTime = Instant.now().toString();
    }

    public String getFlowLogId() { return flowLogId; }
    public String getResourceId() { return resourceId; }
    public String getResourceType() { return resourceType; }
    public String getTrafficType() { return trafficType; }
    public String getLogDestinationType() { return logDestinationType; }
    public String getLogDestination() { return logDestination; }
    public String getLogGroupName() { return logGroupName; }
    public String getDeliverLogsStatus() { return deliverLogsStatus; }
    public String getFlowLogStatus() { return flowLogStatus; }
    public String getCreationTime() { return creationTime; }
}
