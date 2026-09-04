package org.helioviewer.jhv.event;

import java.util.List;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.math.Vec3;

public class JHVPositionInformation {

    private final Vec3 centralPoint;
    private final float[] boundBox;
    private final Position earthPosition;

    public JHVPositionInformation(Vec3 _centralPoint, List<Vec3> _boundBox, Position p) {
        centralPoint = _centralPoint;

        int len = _boundBox.size();
        boundBox = new float[3 * len];
        for (int i = 0; i < len; i++) {
            Vec3 pt = _boundBox.get(i);
            boundBox[3 * i] = (float) pt.x;
            boundBox[3 * i + 1] = (float) pt.y;
            boundBox[3 * i + 2] = (float) pt.z;
        }
        earthPosition = p;
    }

    public Vec3 centralPoint() {
        return centralPoint;
    }

    public float[] getBoundBox() {
        return boundBox;
    }

    public Position getEarth() {
        return earthPosition;
    }

}
