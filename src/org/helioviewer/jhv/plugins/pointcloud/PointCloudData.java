package org.helioviewer.jhv.plugins.pointcloud;

import org.helioviewer.jhv.time.JHVTime;

// One loaded point cloud, preprocessed for fast alpha filtering.
//
// Points are stored in JHV scene coordinates (x = cosLat sinLon, y = solar north,
// z = cosLat cosLon), ready for GLSLShape.putVertex without further transform.
// Tetrahedra are sorted by circumradius ascending, so a given alpha selects the
// prefix tets[0 .. 4*K) with radii below the threshold. faceKey holds, in the same
// tet order, the four boundary-face keys of each tet (packed sorted vertex triple),
// so mesh extraction is a prefix-copy + sort + count-once scan (see PointCloudMesh).
record PointCloudData(JHVTime time, String name, String dataName, String dataUnit,
                      int numPoints, float[] scenePos, float[] values,
                      float[] radiiSorted, long[] faceKey, double dataMin, double dataMax) {

    boolean hasValues() {
        return values != null;
    }

    int numTets() {
        return radiiSorted.length;
    }
}
