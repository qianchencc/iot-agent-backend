package ltd.qianc.iotagent.iot;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class DeviceCatalogSourceBehaviorTest {
    @Test void modelFamiliesAreNormalizedWithoutMergingDifferentHardwareRecords() {
        String body = """
                {"code":200,"content":[
                  {"id":"fixture-light-a","name":"测试照明甲","modelCode":"SL-SW-NS3","installAddress":"测试楼-机房"},
                  {"id":"fixture-light-b","name":"测试照明乙","modelCode":"SL-SW-NS3","installAddress":null},
                  {"id":"fixture-water","name":"测试水浸","modelCode":"YQ-WFS"},
                  {"id":"fixture-meter","name":"测试电表","modelCode":"ENERGY-376.1"},
                  {"id":"fixture-smoke","name":"测试烟感","modelCode":"YQ-PSD"},
                  {"id":"fixture-camera","name":"测试监控","modelCode":"HIK-ISC-VIDEO"},
                  {"id":"fixture-ac","name":"测试空调甲","modelCode":"YQ_AIR_CONDITION"},
                  {"id":"fixture-env","name":"测试环境","modelCode":"YQ-ENV-SENSOR"},
                  {"id":"fixture-door","name":"测试门禁甲","modelCode":"EHE-ACCESS-V2"},
                  {"id":"fixture-ac-controller","name":"测试空调乙","modelCode":"MG-AC-CONTROLLER"},
                  {"id":"fixture-gas","name":"测试燃气","modelCode":"YQ-GAS-INDUCTOR"},
                  {"id":"fixture-door-camera","name":"测试门禁乙","modelCode":"DS-K1T673M"},
                  {"id":"fixture-service","name":"测试事件服务","modelCode":"HIK-ISC-EVENT-SERVICE"},
                  {"id":"fixture-deleted","name":"测试已删除","modelCode":"SL-SW-NS3","isDelete":"TRUE"}
                ]}
                """;
        DeviceCatalogSource source = source(new AtomicReference<>(body));
        List<DeviceCatalogSource.Entry> devices = source.collect(identity());
        assertEquals(List.of("lighting", "lighting", "safety", "energy", "safety", "video", "ac", "environment", "access", "ac", "safety", "access"),
                devices.stream().map(DeviceCatalogSource.Entry::kind).toList());
        assertEquals("fixture-light-a", devices.getFirst().id());
        assertEquals("测试楼-机房", devices.getFirst().location());
        assertEquals("", devices.get(1).location());
        assertEquals("fixture-light-b", devices.get(1).id());
    }

    @Test void partialMalformedAndDuplicateResponsesAreNotPublishedAsComplete() {
        AtomicReference<String> body = new AtomicReference<>("{\"code\":200,\"content\":[]}");
        DeviceCatalogSource source = source(body);
        assertEquals(List.of(), source.collect(identity()));
        for (String invalid : List.of(
                "{\"code\":503,\"content\":[]}",
                "{\"code\":200,\"content\":{\"content\":[],\"last\":false}}",
                "{\"code\":200,\"content\":[null]}",
                "{\"code\":200,\"content\":[{\"id\":\"fixture-a\",\"modelCode\":\"SL-SW-NS3\"}]}",
                "{\"code\":200,\"content\":[{\"id\":\"fixture-a\",\"name\":\"测试照明\",\"modelCode\":\"SL-SW-NS3\"},{\"id\":\"fixture-a\",\"name\":\"测试照明\",\"modelCode\":\"SL-SW-NS3\"}]}")) {
            body.set(invalid);
            assertThrows(UpstreamUnavailableException.class, () -> source.collect(identity()));
        }
    }

    private static DeviceCatalogSource source(AtomicReference<String> body) {
        WebClient client = WebClient.builder().exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(body.get()).build())).build();
        return DeviceCatalogSource.http(client, "/device/list", Duration.ofSeconds(5));
    }
    private static RequestIdentity identity() {
        return new RequestIdentity("Bearer fixture-catalog", "fixture-tenant", IotSourceEnum.COMPANY, "fixture-owner");
    }
}
