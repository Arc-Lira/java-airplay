package wtf.nanoka.airplay.server.internal.handler.util;

import com.dd.plist.BinaryPropertyListParser;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import wtf.nanoka.airplay.lib.AirPlayFeatures;
import wtf.nanoka.airplay.lib.AirPlayIdentity;
import wtf.nanoka.airplay.server.AirPlayConfig;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertyListUtilTest {

    @Test
    void autoHevcAdvertisesUhdWithoutReducingSixtyFps() throws Exception {
        var config = new AirPlayConfig();
        config.setWidth("auto");
        config.setHeight("auto");
        config.setFps("auto");
        config.setHevc(true);
        assertDisplay(config, 3840, 2160, 60);
        config.setHevc(false);
        assertDisplay(config, 1920, 1080, 60);
    }

    @ParameterizedTest
    @CsvSource({"2796,1290", "2732,2048", "2048,2732"})
    void keepsExplicitHighResolutionAndFrameRateWithHevc(int width, int height) throws Exception {
        var config = new AirPlayConfig();
        config.setHevc(true);
        config.setWidth(Integer.toString(width));
        config.setHeight(Integer.toString(height));
        config.setFps("60");
        assertDisplay(config, width, height, 60);
    }

    private void assertDisplay(AirPlayConfig config, int width, int height, int fps) throws Exception {
        config.validate();
        NSDictionary info = (NSDictionary) BinaryPropertyListParser.parse(
                PropertyListUtil.prepareInfoResponse(config, AirPlayIdentity.random(), false));
        NSDictionary display = (NSDictionary) ((NSArray) info.get("displays")).objectAtIndex(0);
        assertEquals(width, ((Number) display.get("width").toJavaObject()).intValue());
        assertEquals(width, ((Number) display.get("widthPixels").toJavaObject()).intValue());
        assertEquals(height, ((Number) display.get("height").toJavaObject()).intValue());
        assertEquals(height, ((Number) display.get("heightPixels").toJavaObject()).intValue());
        assertEquals(fps, ((Number) display.get("maxFPS").toJavaObject()).intValue());
        assertEquals(1.0 / fps, ((Number) display.get("refreshRate").toJavaObject()).doubleValue(), 0.000001);
    }

    @Test
    void advertisesHevcInInfoAndEmbeddedTxtRecords() throws Exception {
        var config = new AirPlayConfig();
        config.setHevc(true);
        AirPlayIdentity identity = AirPlayIdentity.random();

        NSDictionary info = (NSDictionary) BinaryPropertyListParser.parse(
                PropertyListUtil.prepareInfoResponse(config, identity, false));
        Number features = (Number) info.get("features").toJavaObject();
        assertEquals(AirPlayFeatures.receiverMask(true), features.longValue());

        NSDictionary txtInfo = (NSDictionary) BinaryPropertyListParser.parse(
                PropertyListUtil.prepareInfoResponse(config, identity, true));
        byte[] txtRecord = (byte[]) txtInfo.get("txtAirPlay").toJavaObject();
        assertTrue(new String(txtRecord, StandardCharsets.ISO_8859_1)
                .contains("features=" + AirPlayFeatures.txtValue(true)));
    }
}
