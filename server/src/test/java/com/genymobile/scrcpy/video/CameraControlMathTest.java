package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.control.PositionMapper;
import com.genymobile.scrcpy.model.Point;
import com.genymobile.scrcpy.model.Position;
import com.genymobile.scrcpy.model.Size;
import com.genymobile.scrcpy.util.AffineMatrix;

import org.junit.Assert;
import org.junit.Test;

public class CameraControlMathTest {
    @Test
    public void exposureStepsClampAtDeviceLimits() {
        Assert.assertEquals(126, CameraControlMath.step(100, 1, 50, 3188));
        Assert.assertEquals(100, CameraControlMath.step(126, -1, 50, 3188));
        Assert.assertEquals(50, CameraControlMath.step(50, -1, 50, 3188));
        Assert.assertEquals(3188, CameraControlMath.step(3188, 1, 50, 3188));
        Assert.assertEquals(1_000_000_000L, CameraControlMath.step(900_000_000L, 1, 24008, 1_000_000_000L));
        Assert.assertEquals(24008, CameraControlMath.step(24008, -1, 24008, 1_000_000_000L));
    }

    @Test
    public void meteringAccountsForStreamAspectRatioAndSensorOffset() {
        Size stream = new Size(1920, 1080);
        // A 16:9 stream on a 4:3 sensor crops 375 pixels from top and bottom.
        Assert.assertEquals(new Point(2000, 1500), CameraControlMath.meteringPoint(new Point(960, 540), stream, 0, 0, 4000, 3000));
        Assert.assertEquals(new Point(0, 375), CameraControlMath.meteringPoint(new Point(0, 0), stream, 0, 0, 4000, 3000));
        Assert.assertEquals(new Point(20, 385), CameraControlMath.meteringPoint(new Point(0, 0), stream, 20, 10, 4000, 3000));
        // A square stream is cropped horizontally instead.
        Assert.assertEquals(new Point(500, 0), CameraControlMath.meteringPoint(new Point(0, 0), new Size(1000, 1000), 0, 0, 4000, 3000));
    }

    @Test
    public void staleFramesAndBlackBordersCannotMoveMetering() {
        Size size = new Size(1920, 1080);
        Assert.assertNull(CameraControlMath.meteringPoint(new Point(-1, 30), size, 0, 0, 4000, 3000));
        Assert.assertNull(CameraControlMath.meteringPoint(new Point(1920, 30), size, 0, 0, 4000, 3000));
        PositionMapper mapper = PositionMapper.create(size, null, size);
        Assert.assertNull(mapper.map(new Position(100, 200, 1080, 1920)));
    }

    @Test
    public void clicksUndoRotationAndMirroringBeforeSensorMapping() {
        Size size = new Size(1000, 1000);
        PositionMapper flipped = PositionMapper.create(size, AffineMatrix.hflip(), size);
        Assert.assertEquals(new Point(750, 400), flipped.map(new Position(250, 400, 1000, 1000)));
        PositionMapper rotated = PositionMapper.create(size, AffineMatrix.rotateOrtho(1), size);
        Assert.assertEquals(new Point(400, 750), rotated.map(new Position(250, 400, 1000, 1000)));
    }
}
