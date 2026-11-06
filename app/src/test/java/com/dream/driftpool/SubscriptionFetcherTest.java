package com.dream.driftpool;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class SubscriptionFetcherTest {
    private static final String URL="https://a.example/s";
    static class Reply extends HttpURLConnection {
        final int status;final String info,body,location;boolean read,closed;
        Reply(int status,String info,String body,String location)throws Exception{super(new java.net.URL(URL));this.status=status;this.info=info;this.body=body;this.location=location;}
        @Override public int getResponseCode(){return status;}
        @Override public String getHeaderField(String key){return key.equals("Subscription-Userinfo")?info:key.equals("Location")?location:null;}
        @Override public InputStream getInputStream(){read=true;return new ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        @Override public void disconnect(){closed=true;}
        @Override public void connect(){}
        @Override public boolean usingProxy(){return false;}
    }
    @Test public void headRefreshReadsMetadataWithoutDownloadingServers()throws Exception{
        Reply reply=new Reply(200,"upload=10;download=20;total=100","large body",null);
        assertEquals(30,SubscriptionFetcher.fetchUsage(URL,uri->reply).used());
        assertEquals("HEAD",reply.getRequestMethod());assertFalse(reply.read);assertTrue(reply.closed);
    }
    @Test public void missingMetadataAndUnsupportedHeadFallBackToGet()throws Exception{
        for(int code:List.of(200,405,501)){
            Reply head=new Reply(code,null,"",null),get=new Reply(200,null,"#subscription-userinfo: total=100\nvless://fixture",null);
            List<Reply> queue=new ArrayList<>(List.of(head,get));
            assertEquals(100,SubscriptionFetcher.fetchUsage(URL,uri->queue.remove(0)).total());
            assertEquals("HEAD",head.getRequestMethod());assertEquals("GET",get.getRequestMethod());
            assertTrue(get.read);assertTrue(head.closed);assertTrue(get.closed);assertTrue(queue.isEmpty());
        }
    }
    @Test public void followsHttpsRedirectsAndOnlyUsesFinalMetadata()throws Exception{
        Reply redirect=new Reply(302,"total=999","","/new"),finalReply=new Reply(200,"total=100","vless://fixture",null);
        List<URI> addresses=new ArrayList<>();List<Reply> queue=new ArrayList<>(List.of(redirect,finalReply));
        var response=SubscriptionFetcher.fetchWithInfo(URL,uri->{addresses.add(uri);return queue.remove(0);});
        assertEquals(100,response.usage().total());assertEquals("vless://fixture",response.body());
        assertEquals(List.of(URI.create(URL),URI.create("https://a.example/new")),addresses);
        assertTrue(redirect.closed);assertTrue(finalReply.closed);
    }
    @Test public void rejectsDowngradesAndOversizedBodies()throws Exception{
        Reply redirect=new Reply(302,null,"","http://a.example/plain");
        assertThrows(IllegalArgumentException.class,()->SubscriptionFetcher.fetchWithInfo(URL,uri->redirect));
        assertTrue(redirect.closed);
        Reply oversized=new Reply(200,null,"x".repeat(Subscription.MAX_BYTES+1),null);
        assertThrows(IllegalArgumentException.class,()->SubscriptionFetcher.fetchWithInfo(URL,uri->oversized));
        assertTrue(oversized.closed);
    }
}
