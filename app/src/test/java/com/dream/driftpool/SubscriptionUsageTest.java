package com.dream.driftpool;

import org.json.JSONObject;
import org.junit.Test;
import java.util.Base64;
import java.util.List;
import static org.junit.Assert.*;

public class SubscriptionUsageTest {
    private static final String NODE = "vless://12345678-1234-4234-8234-123456789abc@one.example:443?security=tls&type=tcp#One";
    @Test public void parsesProviderBytesAndDistinguishesUnlimitedFromMissing() throws Exception {
        SubscriptionUsage usage=SubscriptionUsage.parse(" Download = 2048 ; upload=1024; total=8192; expire=2000000000; future=value");
        assertEquals(3072,usage.used()); assertEquals(8192,usage.total()); assertEquals(375,usage.progress());
        assertEquals(2000000000L,usage.expire()); assertEquals(usage,SubscriptionUsage.fromJson(usage.toJson()));
        assertEquals(0,SubscriptionUsage.parse("total=0; expire=0").total());
        assertEquals(-1,SubscriptionUsage.parse(null).total());
        assertEquals(-1,SubscriptionUsage.parse("upload=10; total=100").used());
        assertEquals(-1,SubscriptionUsage.fromJson(new JSONObject()).total());
    }
    @Test public void rejectsInvalidAndDuplicateValuesWithoutInventingZero() {
        for(String value:List.of("total=-1","total=NaN","total=1.5","total=1e9","total=9223372036854775808","total=0;total=100"))
            assertEquals(value,-1,SubscriptionUsage.parse(value).total());
        assertEquals(-1,SubscriptionUsage.parse("expire=9223372036854775807").expire());
        assertEquals(SubscriptionUsage.UNKNOWN,SubscriptionUsage.parse("x".repeat(4097)));
        SubscriptionUsage overflow=SubscriptionUsage.parse("upload=9223372036854775807;download=1;total=100");
        assertEquals(Long.MAX_VALUE,overflow.used()); assertEquals(1000,overflow.progress());
    }
    @Test public void supportsPlainAndBase64MetadataWithoutTreatingItAsAServer() throws Exception {
        String body="#profile-title: Provider\n#subscription-userinfo: upload=5;download=7;total=100\n"+NODE;
        for(String text:List.of(body,Base64.getEncoder().encodeToString(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)))) {
            assertEquals(1,Subscription.parse(text).size());
            assertEquals(12,SubscriptionUsage.fromResponse(null,text).used());
            assertEquals(200,SubscriptionUsage.fromResponse("total=200",text).total());
        }
        assertEquals(SubscriptionUsage.UNKNOWN,SubscriptionUsage.fromResponse(null,"#subscription-userinfo: total=100\n#subscription-userinfo: total=0\n"+NODE));
        assertThrows(Exception.class,()->Subscription.parse(body+"\ntrojan://unsupported"));
    }
    @Test public void usageSurvivesStorageMigrationAndFailedSourceRefresh() throws Exception {
        SubscriptionUsage usage=SubscriptionUsage.parse("upload=5;download=7;total=100");
        SubscriptionBook book=new SubscriptionBook(List.of()).upsert("https://a.example/s",NODE,100,usage);
        assertEquals(usage,SubscriptionBook.fromJson(book.toJson()).sources().get(0).usage());
        JSONObject old=book.toJson();old.getJSONArray("sources").getJSONObject(0).remove("usage");
        assertEquals(SubscriptionUsage.UNKNOWN,SubscriptionBook.fromJson(old).sources().get(0).usage());
        SubscriptionRefresh.Result result=SubscriptionRefresh.run(book,List.of("https://a.example/s","https://b.example/s"),url->{
            if(url.contains("a.example"))throw new java.io.IOException();
            return new SubscriptionFetcher.Response(NODE,SubscriptionUsage.parse("total=0"));
        });
        assertEquals(1,result.succeeded());assertEquals(1,result.failed());
        assertEquals(usage,result.book().sources().get(0).usage());assertEquals(0,result.book().sources().get(1).usage().total());
    }
    @Test public void usageRefreshCannotResurrectSourcesOverwriteNewerDataOrChangeServers() throws Exception {
        SubscriptionBook book=new SubscriptionBook(List.of()).upsert("https://a.example/s",NODE,100,SubscriptionUsage.parse("total=100"));
        SubscriptionBook.Source source=book.sources().get(0);
        var old=new SubscriptionRefresh.UsageUpdate(source.id(),source.url(),SubscriptionUsage.parse("total=50"),99);
        assertEquals(book,book.withUsage(List.of(old)));
        var fresh=new SubscriptionRefresh.UsageUpdate(source.id(),source.url(),SubscriptionUsage.parse("total=200"),200);
        SubscriptionBook updated=book.withUsage(List.of(fresh,old));
        assertEquals(200,updated.sources().get(0).usage().total());
        assertEquals(source.body(),updated.sources().get(0).body());assertEquals(source.updatedAt(),updated.sources().get(0).updatedAt());
        assertEquals(book.nodes().toString(),updated.nodes().toString());
        assertTrue(book.remove(source.id()).withUsage(List.of(fresh)).sources().isEmpty());
        SubscriptionBook readded=book.remove(source.id()).upsert(source.url(),NODE,300);
        assertEquals(readded,readded.withUsage(List.of(fresh)));
        var failure=SubscriptionRefresh.usage(book.sources(),url->{throw new java.io.IOException();});
        assertTrue(failure.isEmpty());assertEquals(book,book.withUsage(failure));
    }
}
