package wtf.nanoka.airplay.player.gstreamer.ui;

import org.junit.jupiter.api.Test;

import java.awt.Canvas;
import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VisionPlayerWindowTest {

    @Test
    void fitsLandscapeVideoInsideAvailableSurface() {
        var panel = new VisionPlayerWindow.VideoSurfacePanel();
        var canvas = new Canvas();
        panel.add(canvas);
        panel.setAspectRatio(16, 9);
        panel.setSize(1000, 600);

        panel.doLayout();

        assertEquals(new Rectangle(0, 18, 1000, 563), canvas.getBounds());
    }

    @Test
    void centersPortraitVideoWithoutCropping() {
        var panel = new VisionPlayerWindow.VideoSurfacePanel();
        var canvas = new Canvas();
        panel.add(canvas);
        panel.setAspectRatio(9, 16);
        panel.setSize(600, 600);

        panel.doLayout();

        assertEquals(new Rectangle(131, 0, 338, 600), canvas.getBounds());
    }

    @Test
    void preservesIpadAspectRatioWhenRotatingInTheSameWindow() {
        var panel = new VisionPlayerWindow.VideoSurfacePanel();
        var canvas = new Canvas();
        panel.add(canvas);
        panel.setSize(1000, 600);

        panel.setAspectRatio(2732, 2048);
        panel.doLayout();
        assertEquals(new Rectangle(100, 0, 800, 600), canvas.getBounds());

        panel.setAspectRatio(2048, 2732);
        panel.doLayout();
        assertEquals(new Rectangle(275, 0, 450, 600), canvas.getBounds());

        panel.setAspectRatio(2048, 1536);
        panel.doLayout();
        assertEquals(new Rectangle(100, 0, 800, 600), canvas.getBounds());
    }
}
