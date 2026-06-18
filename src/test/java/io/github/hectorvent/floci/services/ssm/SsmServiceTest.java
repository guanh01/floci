package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ssm.model.Parameter;
import io.github.hectorvent.floci.services.ssm.model.ParameterHistory;
import io.github.hectorvent.floci.services.ssm.model.ServiceSetting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SsmServiceTest {

    private SsmService ssmService;

    @BeforeEach
    void setUp() {
        ssmService = new SsmService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                5
        );
    }

    @Test
    void putAndGetParameter() {
        String region = "eu-west-1";
        ssmService.putParameter("/app/db/host", "localhost", "String", null, false, region);
        Parameter param = ssmService.getParameter("/app/db/host", region);

        assertEquals("/app/db/host", param.getName());
        assertEquals("localhost", param.getValue());
        assertEquals("String", param.getType());
        assertEquals(1, param.getVersion());
        assertNotNull(param.getLastModifiedDate());
    }

    @Test
    void putParameterOverwrite() {
        String region = "eu-west-1";
        ssmService.putParameter("/app/key", "v1", "String", null, false, region);
        ssmService.putParameter("/app/key", "v2", "String", null, true, region);
        Parameter param = ssmService.getParameter("/app/key", region);

        assertEquals("v2", param.getValue());
        assertEquals(2, param.getVersion());
    }

    @Test
    void putParameterWithoutOverwriteThrows() {
        String region = "eu-west-1";
        ssmService.putParameter("/app/key", "v1", "String", null, false, region);
        assertThrows(AwsException.class, () ->
                ssmService.putParameter("/app/key", "v2", "String", null, false, region));
    }

    @Test
    void getParameterNotFound() {
        AwsException ex = assertThrows(AwsException.class, () ->
                ssmService.getParameter("/nonexistent", "eu-west-1"));
        assertEquals("ParameterNotFound", ex.getErrorCode());
    }

    @Test
    void getParameters() {
        String region = "eu-west-1";
        ssmService.putParameter("/a", "1", "String", null, false, region);
        ssmService.putParameter("/b", "2", "String", null, false, region);
        ssmService.putParameter("/c", "3", "String", null, false, region);

        List<Parameter> params = ssmService.getParameters(List.of("/a", "/c", "/missing"), region);
        assertEquals(2, params.size());
    }

    @Test
    void getParametersByPathRecursive() {
        String region = "eu-west-1";
        ssmService.putParameter("/app/db/host", "localhost", "String", null, false, region);
        ssmService.putParameter("/app/db/port", "5432", "String", null, false, region);
        ssmService.putParameter("/app/db/nested/deep", "value", "String", null, false, region);
        ssmService.putParameter("/app/cache/host", "redis", "String", null, false, region);

        List<Parameter> results = ssmService.getParametersByPath("/app/db", true, region);
        assertEquals(3, results.size());
    }

    @Test
    void getParametersByPathNonRecursive() {
        String region = "eu-west-1";
        ssmService.putParameter("/app/db/host", "localhost", "String", null, false, region);
        ssmService.putParameter("/app/db/port", "5432", "String", null, false, region);
        ssmService.putParameter("/app/db/nested/deep", "value", "String", null, false, region);

        List<Parameter> results = ssmService.getParametersByPath("/app/db", false, region);
        assertEquals(2, results.size());
    }

    @Test
    void deleteParameter() {
        String region = "eu-west-1";
        ssmService.putParameter("/app/key", "value", "String", null, false, region);
        ssmService.deleteParameter("/app/key", region);
        assertThrows(AwsException.class, () -> ssmService.getParameter("/app/key", region));
    }

    @Test
    void deleteParameterNotFoundThrows() {
        assertThrows(AwsException.class, () -> ssmService.deleteParameter("/missing", "eu-west-1"));
    }

    @Test
    void deleteParameters() {
        String region = "eu-west-1";
        ssmService.putParameter("/a", "1", "String", null, false, region);
        ssmService.putParameter("/b", "2", "String", null, false, region);

        List<String> deleted = ssmService.deleteParameters(List.of("/a", "/missing"), region);
        assertEquals(1, deleted.size());
        assertEquals("/a", deleted.getFirst());
    }

    @Test
    void getParameterHistory() {
        String region = "eu-west-1";
        ssmService.putParameter("/app/key", "v1", "String", null, false, region);
        ssmService.putParameter("/app/key", "v2", "String", null, true, region);
        ssmService.putParameter("/app/key", "v3", "String", null, true, region);

        List<ParameterHistory> history = ssmService.getParameterHistory("/app/key", region);
        assertEquals(3, history.size());
        assertEquals("v1", history.get(0).getValue());
        assertEquals("v3", history.get(2).getValue());
    }

    @Test
    void parameterHistoryIsTrimmedToMax() {
        String region = "eu-west-1";
        for (int i = 1; i <= 7; i++) {
            ssmService.putParameter("/app/key", "v" + i, "String", null, i == 1 ? false : true, region);
        }

        List<ParameterHistory> history = ssmService.getParameterHistory("/app/key", region);
        assertEquals(5, history.size());
        assertEquals("v3", history.get(0).getValue());
        assertEquals("v7", history.get(4).getValue());
    }

    // ── Service Setting Tests ─────────────────────────────────────────────────

    @Test
    void getServiceSettingReturnsDefaultWhenNotSet() {
        String region = "us-east-1";
        ServiceSetting setting = ssmService.getServiceSetting("/ssm/opsdata/Association", region);

        assertEquals("/ssm/opsdata/Association", setting.getSettingId());
        assertEquals("", setting.getSettingValue());
        assertEquals("Default", setting.getStatus());
    }

    @Test
    void updateAndGetServiceSetting() {
        String region = "us-east-1";
        ssmService.updateServiceSetting("/ssm/opsdata/Association", "Enabled", region);

        ServiceSetting setting = ssmService.getServiceSetting("/ssm/opsdata/Association", region);
        assertEquals("/ssm/opsdata/Association", setting.getSettingId());
        assertEquals("Enabled", setting.getSettingValue());
        assertEquals("Customized", setting.getStatus());
        assertNotNull(setting.getLastModifiedDate());
        assertNotNull(setting.getArn());
    }

    @Test
    void updateServiceSettingOverwritesExisting() {
        String region = "us-east-1";
        ssmService.updateServiceSetting("/ssm/opsdata/Association", "Enabled", region);
        ssmService.updateServiceSetting("/ssm/opsdata/Association", "Disabled", region);

        ServiceSetting setting = ssmService.getServiceSetting("/ssm/opsdata/Association", region);
        assertEquals("Disabled", setting.getSettingValue());
        assertEquals("Customized", setting.getStatus());
    }

    @Test
    void resetServiceSettingRestoresDefault() {
        String region = "us-east-1";
        ssmService.updateServiceSetting("/ssm/opsdata/Association", "Enabled", region);
        ssmService.resetServiceSetting("/ssm/opsdata/Association", region);

        ServiceSetting setting = ssmService.getServiceSetting("/ssm/opsdata/Association", region);
        assertEquals("Default", setting.getStatus());
        assertEquals("", setting.getSettingValue());
    }

    @Test
    void multipleServiceSettingsAreIndependent() {
        String region = "us-east-1";
        ssmService.updateServiceSetting("/ssm/opsdata/Association", "Enabled", region);
        ssmService.updateServiceSetting("/ssm/opsitem/ssm-patchmanager", "Enabled", region);

        ServiceSetting s1 = ssmService.getServiceSetting("/ssm/opsdata/Association", region);
        ServiceSetting s2 = ssmService.getServiceSetting("/ssm/opsitem/ssm-patchmanager", region);

        assertEquals("Enabled", s1.getSettingValue());
        assertEquals("Enabled", s2.getSettingValue());
    }

    @Test
    void serviceSettingsAreRegionScoped() {
        ssmService.updateServiceSetting("/ssm/opsdata/Association", "Enabled", "us-east-1");

        ServiceSetting east = ssmService.getServiceSetting("/ssm/opsdata/Association", "us-east-1");
        ServiceSetting west = ssmService.getServiceSetting("/ssm/opsdata/Association", "us-west-2");

        assertEquals("Enabled", east.getSettingValue());
        assertEquals("Customized", east.getStatus());
        assertEquals("", west.getSettingValue());
        assertEquals("Default", west.getStatus());
    }

    @Test
    void seedServiceSetting() {
        String region = "us-east-1";
        ssmService.seedServiceSetting("/ssm/opsdata/Association", "Enabled", "Customized",
                "arn:aws:ssm:us-east-1:123456789012:servicesetting/ssm/opsdata/Association", region);

        ServiceSetting setting = ssmService.getServiceSetting("/ssm/opsdata/Association", region);
        assertEquals("Enabled", setting.getSettingValue());
        assertEquals("Customized", setting.getStatus());
        assertEquals("arn:aws:ssm:us-east-1:123456789012:servicesetting/ssm/opsdata/Association", setting.getArn());
    }
}
