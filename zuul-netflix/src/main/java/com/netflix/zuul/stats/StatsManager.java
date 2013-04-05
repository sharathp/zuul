package com.netflix.zuul.stats;

import com.netflix.zuul.context.RequestContext;
import com.netflix.zuul.stats.monitoring.MonitorRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.powermock.api.mockito.PowerMockito.when;


/**
 * Created by IntelliJ IDEA.
 * User: mcohen
 * Date: 2/3/12
 * Time: 3:25 PM
 * To change this template use File | Settings | File Templates.
 */
public class StatsManager {


    private static final Logger LOG = LoggerFactory.getLogger(StatsManager.class);


    protected static final Pattern HEX_PATTERN = Pattern.compile("[0-9a-fA-F]+");

    // should match *.amazonaws.com, *.nflxvideo.net, or raw IP addresses.
    private static final Pattern HOST_PATTERN =
        Pattern.compile("(?:(.+)\\.amazonaws\\.com)|((?:\\d{1,3}\\.?){4})|(ip-\\d+-\\d+-\\d+-\\d+)|" +
            "(?:(.+)\\.nflxvideo\\.net)|(?:(.+)\\.llnwd\\.net)|(?:(.+)\\.nflximg\\.com)");

    private static final String HOST_HEADER = "host";

    private static final String X_FORWARDED_FOR_HEADER = "x-forwarded-for";

    private static final String X_FORWARDED_PROTO_HEADER = "x-forwarded-proto";

    private final ConcurrentMap<String, ConcurrentHashMap<Integer, RouteStatusCodeMonitor>> routeStatusMap =
        new ConcurrentHashMap<String, ConcurrentHashMap<Integer, RouteStatusCodeMonitor>>();

    // this is superceded by namedStatusMap and will eventually be removed
    private final ConcurrentMap<Integer, StatusCodeMonitor> statusMap = new ConcurrentHashMap<Integer, StatusCodeMonitor>();

    private final ConcurrentMap<String, NamedCountingMonitor> namedStatusMap =
            new ConcurrentHashMap<String, NamedCountingMonitor>();

    private final ConcurrentMap<String, NamedCountingMonitor> hostCounterMap =
        new ConcurrentHashMap<String, NamedCountingMonitor>();

    private final ConcurrentMap<String, NamedCountingMonitor> protocolCounterMap =
        new ConcurrentHashMap<String, NamedCountingMonitor>();

    private final ConcurrentMap<String, NamedCountingMonitor> ipVersionCounterMap =
            new ConcurrentHashMap<String, NamedCountingMonitor>();

    private final ConcurrentMap<String, NamedCountingMonitor> countryCounterMap =
            new ConcurrentHashMap<String, NamedCountingMonitor>();

    private final ConcurrentMap<String, NamedCountingMonitor> transferEncodingCounterMap =
            new ConcurrentHashMap<String, NamedCountingMonitor>();

    protected static StatsManager INSTANCE = new StatsManager();

    public static StatsManager getManager() {
        return INSTANCE;
    }

    public RouteStatusCodeMonitor getRouteStatusCodeMonitor(String route, int statusCode) {
        Map<Integer, RouteStatusCodeMonitor> map = routeStatusMap.get(route);
        if (map == null) return null;
        return map.get(statusCode);
    }

    private NamedCountingMonitor getHostMonitor(String host) {
        return this.hostCounterMap.get(hostKey(host));
    }

    private NamedCountingMonitor getProtocolMonitor(String proto) {
        return this.protocolCounterMap.get(protocolKey(proto));
    }

    private static final String hostKey(String host) {
        try {
            final Matcher m = HOST_PATTERN.matcher(host);

            // I know which type of host matched by the number of the group that is non-null
            // I use a different replacement string per host type to make the Epic stats more clear
            if (m.matches()) {
                if (m.group(1) != null) host = host.replace(m.group(1), "EC2");
                else if (m.group(2) != null) host = host.replace(m.group(2), "IP");
                else if (m.group(3) != null) host = host.replace(m.group(3), "IP");
                else if (m.group(4) != null) host = host.replace(m.group(4), "CDN");
                else if (m.group(5) != null) host = host.replace(m.group(5), "CDN");
                else if (m.group(6) != null) host = host.replace(m.group(6), "CDN");
            }
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
        } finally {
            return String.format("host_%s", host);
        }
    }

    private static final String protocolKey(String proto) {
        return String.format("protocol_%s", proto);
    }



    public void collectRequestStats(HttpServletRequest req) {
        // ipv4/ipv6 tracking
        String clientIp;
        final String xForwardedFor = req.getHeader(X_FORWARDED_FOR_HEADER);
        if(xForwardedFor == null) {
            clientIp = req.getRemoteAddr();
        } else {
            clientIp = extractClientIpFromXForwardedFor(xForwardedFor);
        }

        final boolean isIPv6 = (clientIp != null) ? isIPv6(clientIp) : false;

        final String ipVersionKey =  isIPv6 ? "ipv6" : "ipv4";
        incrementNamedCountingMonitor(ipVersionKey, ipVersionCounterMap);

        // host header
        String host = req.getHeader(HOST_HEADER);
        if (host != null) {
            int colonIdx;
            if(isIPv6) {
                // an ipv6 host might be a raw IP with 7+ colons
                colonIdx = host.lastIndexOf(":");
            } else {
                // strips port from host
                colonIdx = host.indexOf(":");
            }
            if (colonIdx > -1) host = host.substring(0, colonIdx);
            incrementNamedCountingMonitor(hostKey(host), this.hostCounterMap);
        }

        // http vs. https
        String protocol = req.getHeader(X_FORWARDED_PROTO_HEADER);
        if (protocol == null) protocol = req.getScheme();
        incrementNamedCountingMonitor(protocolKey(protocol), this.protocolCounterMap);

        //todo pull out to NF layer
        /*
        // country
        final ISOCountry country = CurrentRequestContext.get().getCountry();
        String countryId = (country != null) ? country.getId() : "UNKNOWN";
        incrementNamedCountingMonitor(String.format("country_%s", countryId), this.countryCounterMap);
        */

        // transfer-encoding
        if(RequestContext.getCurrentContext().isChunkedRequestBody())
            incrementNamedCountingMonitor("request_transfer_encoding_chunked", this.transferEncodingCounterMap);

    }

    private static final boolean isIPv6(String ip) {
        return ip.split(":").length == 8;
    }

    private static final String extractClientIpFromXForwardedFor(String xForwardedFor) {
        return xForwardedFor.split(",")[0];
    }

    /**
     * helper method to create new monitor, place into map, and register wtih Epic, if necessary
     */
    private void incrementNamedCountingMonitor(String name, ConcurrentMap<String, NamedCountingMonitor> map) {
        NamedCountingMonitor monitor = map.get(name);
        if(monitor == null) {
            monitor = new NamedCountingMonitor(name);
            NamedCountingMonitor conflict = map.putIfAbsent(name, monitor);
            if(conflict != null) monitor = conflict;
            else MonitorRegistry.getInstance().registerObject(monitor);
        }
        monitor.increment();
    }

    public void collectRouteStats(String route, int statusCode) {
        // increments status code counter
        StatusCodeMonitor sm = statusMap.get(statusCode);
        if (sm == null) {
            sm = new StatusCodeMonitor(statusCode);
            StatusCodeMonitor found = statusMap.putIfAbsent(statusCode, sm);
            if (found != null) sm = found;
            else MonitorRegistry.getInstance().registerObject(sm);
        }
        sm.update();

        // increments 200, 301, 401, 503, etc. status counters
        final String preciseStatusString = String.format("status_%d", statusCode);
        NamedCountingMonitor preciseStatus = namedStatusMap.get(preciseStatusString);
        if (preciseStatus == null) {
            preciseStatus = new NamedCountingMonitor(preciseStatusString);
            NamedCountingMonitor found = namedStatusMap.putIfAbsent(preciseStatusString, preciseStatus);
            if (found != null) preciseStatus = found;
            else MonitorRegistry.getInstance().registerObject(preciseStatus);
        }
        preciseStatus.increment();

        // increments 2xx, 3xx, 4xx, 5xx status counters
        final String summaryStatusString = String.format("status_%dxx", statusCode / 100);
        NamedCountingMonitor summaryStatus = namedStatusMap.get(summaryStatusString);
        if (summaryStatus == null) {
            summaryStatus = new NamedCountingMonitor(summaryStatusString);
            NamedCountingMonitor found = namedStatusMap.putIfAbsent(summaryStatusString, summaryStatus);
            if (found != null) summaryStatus = found;
            else MonitorRegistry.getInstance().registerObject(summaryStatus);
        }
        summaryStatus.increment();

        // increments route and status counter
        if (route == null) route = "ROUTE_NOT_FOUND";
        route = route.replace("/", "_");
        ConcurrentHashMap<Integer, RouteStatusCodeMonitor> statsMap = routeStatusMap.get(route);
        if (statsMap == null) {
            statsMap = new ConcurrentHashMap<Integer, RouteStatusCodeMonitor>();
            routeStatusMap.putIfAbsent(route, statsMap);
        }
        RouteStatusCodeMonitor sd = statsMap.get(statusCode);
        if (sd == null) {
            //don't register only 404 status codes (these are garbage endpoints)
            if(statusCode == 404){
                if(statsMap.size() == 0){
                    return;
                }
            }

            sd = new RouteStatusCodeMonitor(route, statusCode);
            RouteStatusCodeMonitor sd1 = statsMap.putIfAbsent(statusCode, sd);
            if (sd1 != null) {
                sd = sd1;
            } else {
                MonitorRegistry.getInstance().registerObject(sd);
            }
        }
        sd.update();
    }


    @RunWith(MockitoJUnitRunner.class)
    public static class UnitTest {

        @Test
        public void testCollectRouteStats() {
            String route = "test";
            int status = 500;

            StatsManager sm = StatsManager.getManager();
            assertNotNull(sm);

            // 1st request
            sm.collectRouteStats(route, status);

            ConcurrentHashMap<Integer, RouteStatusCodeMonitor> routeStatusMap = sm.routeStatusMap.get("test");
            assertNotNull(routeStatusMap);

            StatusCodeMonitor statusMonitor = sm.statusMap.get(status);
            assertNotNull("status monitor is null", statusMonitor);

            RouteStatusCodeMonitor routeStatusMonitor = routeStatusMap.get(status);

            assertEquals(routeStatusMonitor.getCount(), 1);
            assertEquals(statusMonitor.getCount(), 1);

            // 2nd request
            sm.collectRouteStats(route, status);

            assertEquals(routeStatusMonitor.getCount(), 2);
            assertEquals(statusMonitor.getCount(), 2);
        }

        @Test
        public void testGetRouteStatusCodeMonitor() {
            StatsManager sm = StatsManager.getManager();
            assertNotNull(sm);
            sm.collectRouteStats("test", 500);
            assertNotNull(sm.getRouteStatusCodeMonitor("test", 500));
        }

        @Test
        public void testCollectRequestStats() {
            final String host = "api.test.netflix.com";
            final String proto = "https";

            final HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
            when(req.getHeader(HOST_HEADER)).thenReturn(host);
            when(req.getHeader(X_FORWARDED_PROTO_HEADER)).thenReturn(proto);
            when(req.getRemoteAddr()).thenReturn("127.0.0.1");

            final StatsManager sm = StatsManager.getManager();
            sm.collectRequestStats(req);

            final NamedCountingMonitor hostMonitor = sm.getHostMonitor(host);
            assertNotNull("hostMonitor should not be null", hostMonitor);

            final NamedCountingMonitor protoMonitor = sm.getProtocolMonitor(proto);
            assertNotNull("protoMonitor should not be null", protoMonitor);

            assertEquals(1, hostMonitor.getCount());
            assertEquals(1, protoMonitor.getCount());
        }

        @Test
        public void createsNormalizedHostKey() {
                        final String host = "api.test.netflix.com";
            assertEquals("host_EC2.amazonaws.com", StatsManager.hostKey("ec2-174-129-179-89.compute-1.amazonaws.com"));
            assertEquals("host_IP", StatsManager.hostKey("12.345.6.789"));
            assertEquals("host_IP", StatsManager.hostKey("ip-10-86-83-168"));
            assertEquals("host_CDN.nflxvideo.net", StatsManager.hostKey("002.ie.llnw.nflxvideo.net"));
            assertEquals("host_CDN.llnwd.net", StatsManager.hostKey("netflix-635.vo.llnwd.net"));
            assertEquals("host_CDN.nflximg.com", StatsManager.hostKey("cdn-0.nflximg.com"));
        }

        @Test
        public void extractsClientIpFromXForwardedFor() {
            final String ip1 = "hi";
            final String ip2 = "hey";
            assertEquals(ip1, StatsManager.extractClientIpFromXForwardedFor(ip1));
            assertEquals(ip1, StatsManager.extractClientIpFromXForwardedFor(String.format("%s,%s", ip1, ip2)));
            assertEquals(ip1, StatsManager.extractClientIpFromXForwardedFor(String.format("%s, %s", ip1, ip2)));
        }

        @Test
        public void isIPv6() {
            assertTrue(StatsManager.isIPv6("0:0:0:0:0:0:0:1"));
            assertTrue(StatsManager.isIPv6("2607:fb10:2:232:72f3:95ff:fe03:a6e7"));
            assertFalse(StatsManager.isIPv6("127.0.0.1"));
            assertFalse(StatsManager.isIPv6("10.2.233.134"));
        }

    }


}