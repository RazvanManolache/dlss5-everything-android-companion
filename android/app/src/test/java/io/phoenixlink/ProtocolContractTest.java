package io.phoenixlink;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

// Source added for later execution. No device, network or GPU is used by these contracts.
public class ProtocolContractTest {
    @Test public void followStateRejectsOldFramesAfterSwitchProtectionAndResize() {
        FollowSourceState s=new FollowSourceState();
        long first=s.select(1,0,10,100,210).generation;assertFalse(s.accepts(first));assertTrue(s.permit(first));assertTrue(s.accepts(first));
        assertEquals(first,s.select(1,0,10,100,210).generation);
        long next=s.select(2,0,10,100,210).generation;assertFalse(s.accepts(first));assertFalse(s.permit(first));assertTrue(s.permit(next));
        s.block(false);assertFalse(s.accepts(next));assertFalse(s.permit(next));
        long unblocked=s.get().generation;assertTrue(s.permit(unblocked));
        long resized=s.select(2,0,10,200,210).generation;assertFalse(s.accepts(unblocked));assertTrue(s.permit(resized));
        s.block(true);assertEquals(-1,s.get().window);assertFalse(s.accepts(resized));assertFalse(s.permit(resized));
    }
    @Test public void pixelsRejectOversizeAndOverflow() throws Exception {
        assertEquals(4,LanClient.pixelBytes(1,1)); assertEquals(33177600,LanClient.pixelBytes(3840,2160));
        for(int[] size:new int[][]{{0,1},{1,0},{-1,9},{16385,1},{7680,4320},{Integer.MAX_VALUE,Integer.MAX_VALUE}}) {
            try { LanClient.pixelBytes(size[0],size[1]); fail("Invalid size accepted"); } catch(IOException expected) { }
        }
    }
    @Test public void fingerprintMustHaveExactlyThirtyTwoBytes() throws Exception {
        assertEquals(32,LanClient.hex("AB".repeat(32)).length);
        for(String bad:new String[]{"", "AA", "GG".repeat(32), "AB".repeat(33)}) {
            try { LanClient.hex(bad); fail("Invalid pin accepted"); } catch(IOException expected) { }
        }
    }
    @Test public void scalePreservesPortraitLandscapeAndNeverUpscales() {
        assertArrayEquals(new int[]{576,1280},FrameGeometry.scaled(1220,2712,1280));
        assertArrayEquals(new int[]{1280,576},FrameGeometry.scaled(2712,1220,1280));
        assertArrayEquals(new int[]{320,200},FrameGeometry.scaled(320,200,1280));
        assertArrayEquals(new int[]{1,1280},FrameGeometry.scaled(1,10000,1280));
    }
    @Test public void connectionAcceptsOnlyPrivateIpv4AndKnownCodec() throws Exception {
        LinkConfig c=new LinkConfig(); c.fingerprint="AB".repeat(32); c.token="CD".repeat(32); c.host="192.168.1.10"; c.validate();
        for(String bad:new String[]{"8.8.8.8","0.0.0.0","192.168.1.256","example.com","::1"}) {
            c.host=bad; try { c.validate(); fail("Public or invalid address accepted"); } catch(IOException expected) { }
        }
        c.host="10.0.0.1"; c.codec="unknown";
        try { c.validate(); fail("Unknown codec accepted"); } catch(IOException expected) { }
    }
}
