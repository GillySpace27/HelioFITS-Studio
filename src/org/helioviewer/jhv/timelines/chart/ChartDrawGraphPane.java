package org.helioviewer.jhv.timelines.chart;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

import javax.swing.JComponent;
import javax.swing.event.MouseInputListener;

import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.event.JHVEventCache;
import org.helioviewer.jhv.gui.UIGlobals;
import org.helioviewer.jhv.movie.ExportMovie;
import org.helioviewer.jhv.timelines.TimelineLayers;
import org.helioviewer.jhv.timelines.draw.ClickableDrawable;
import org.helioviewer.jhv.timelines.draw.DrawConstants;
import org.helioviewer.jhv.timelines.draw.DrawController;
import org.helioviewer.jhv.timelines.draw.GraphGeometry;
import org.helioviewer.jhv.timelines.draw.TimeAxis;

@SuppressWarnings("serial")
final class ChartDrawGraphPane extends JComponent implements MouseInputListener, MouseWheelListener, ComponentListener, DrawController.Listener {

    private enum DragMode {
        MOVIELINE, CHART, TRIM, NODRAG
    }

    private Point mousePressedPosition;
    private boolean chartDragged;
    private boolean trimDraggingEnd; // which trim handle an Option-drag is moving

    private BufferedImage screenImage;

    private final TimelineLabelPainter labelPainter = new TimelineLabelPainter();
    private Point mousePosition;
    private int lastWidth = -1;
    private int lastHeight = -1;

    private boolean redrawGraphArea;

    private DragMode dragMode = DragMode.NODRAG;

    ChartDrawGraphPane() {
        setPreferredSize(new Dimension(-1, 50));
        setOpaque(true);
        setDoubleBuffered(false);

        addMouseListener(this);
        addMouseMotionListener(this);
        addMouseWheelListener(this);
        addComponentListener(this);
        DrawController.addDrawListener(this);
        DrawController.setGraphSize(new Rectangle(getWidth(), getHeight()));

        // Same trim keys as the top scrubber: click the timeline to move the playhead to (say) an
        // instrument's first frame in the coverage track, then I/O to trim there.
        setFocusable(true);
        addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                requestFocusInWindow();
            }
        });
        getInputMap(WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_I, 0), "trimStart");
        getActionMap().put("trimStart", org.helioviewer.jhv.gui.Actions.TRIM_START);
        getInputMap(WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_O, 0), "trimEnd");
        getActionMap().put("trimEnd", org.helioviewer.jhv.gui.Actions.TRIM_END);

        // Show the crop cursor the instant Option is pressed while hovering, not just once the
        // mouse next moves. WHEN_IN_FOCUSED_WINDOW so it fires regardless of which component has
        // keyboard focus — purely visual, so it's safe to bind window-wide.
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ALT, 0, false), "altDown");
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ALT, 0, true), "altUp");
        getActionMap().put("altDown", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (getMousePosition() != null)
                    setCursor(org.helioviewer.jhv.gui.component.TrimCursor.get());
            }
        });
        getActionMap().put("altUp", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (getMousePosition() != null)
                    setCursor(Cursor.getDefaultCursor()); // next mouseMoved refines it further
            }
        });
    }

    @Override
    public void removeNotify() {
        DrawController.removeDrawListener(this);
        super.removeNotify();
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        if (visible) {
            ExportMovie.EVEImage = screenImage;
            DrawController.start();
        } else {
            ExportMovie.EVEImage = null;
            DrawController.stop();
        }
    }

    @Override
    protected void paintComponent(Graphics g1) {
        super.paintComponent(g1);
        GraphGeometry geometry = DrawController.getGeometry();

        if (redrawGraphArea) {
            redrawGraphArea = false;
            redrawGraph(geometry);
        }

        Graphics2D g = (Graphics2D) g1;
        if (screenImage != null) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.drawImage(screenImage, 0, 0, getWidth(), getHeight(), null);
            drawMovieLine(g);
            drawMovieEndpoints(g);
            labelPainter.drawMouseValues(g, geometry, DrawController.selectedAxis, mousePosition);
        }
    }

    private void redrawGraph(GraphGeometry geometry) {
        Rectangle graphArea = geometry.area();
        Rectangle graphSize = geometry.size();
        double sx = Display.pixelScale[0], sy = Display.pixelScale[1];
        int width = (int) (sx * graphSize.getWidth() + .5);
        int height = (int) (sy * graphSize.getHeight() + .5);

        if (width != lastWidth || height != lastHeight) {
            screenImage = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().createCompatibleImage(width, height, Transparency.OPAQUE);
            ExportMovie.EVEImage = screenImage;

            lastWidth = width;
            lastHeight = height;
        }

        Graphics2D fullG = screenImage.createGraphics();
        drawBackground(fullG, screenImage.getWidth(), screenImage.getHeight());

        fullG.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        fullG.setTransform(AffineTransform.getScaleInstance(sx, sy));

        Graphics2D plotG = (Graphics2D) fullG.create();
        plotG.setClip(graphArea);
        plotG.setFont(DrawConstants.font);
        TimeAxis xAxis = DrawController.selectedAxis;
        TimelineLayers.draw(plotG, graphArea, xAxis, mousePosition);
        labelPainter.drawStaticLabels(fullG, geometry, xAxis);

        plotG.dispose();
        fullG.dispose();
    }

    private static void drawBackground(Graphics2D g, int width, int height) {
        g.setColor(UIGlobals.TL_SELECTED_INTERVAL_BACKGROUND_COLOR);
        g.fillRect(0, 0, width, height);
    }

    private static void drawMovieLine(Graphics2D g) {
        int movieLinePosition = DrawController.getMovieLinePosition();
        ExportMovie.EVEMovieLinePosition = movieLinePosition;
        if (movieLinePosition < 0) {
            return;
        }
        g.setColor(UIGlobals.TL_MOVIE_FRAME_COLOR);
        g.drawLine(movieLinePosition, 0, movieLinePosition, DrawController.getGeometry().size().height);
    }

    // Mark the movie's trim (in/out) points as vertical guides with handle triangles, shading the
    // trimmed-away regions. Same playback range as the top scrubber, so trimming from either shows
    // here. Toggled by the "Ends" button.
    private static void drawMovieEndpoints(Graphics2D g) {
        if (!DrawController.isShowMovieEndpoints())
            return;
        long inTime = org.helioviewer.jhv.movie.Player.getPlaybackFirstTime();
        long outTime = org.helioviewer.jhv.movie.Player.getPlaybackLastTime();
        if (outTime <= inTime)
            return;
        java.awt.Rectangle area = DrawController.getGeometry().area();
        org.helioviewer.jhv.timelines.draw.TimeAxis.Mapper m = DrawController.selectedAxis.mapper(area.x, area.width);
        int h = DrawController.getGeometry().size().height;
        int xIn = m.toPixel(inTime);
        int xOut = m.toPixel(outTime);

        // dim the trimmed-away regions (outside [in, out]) within the plot
        g.setColor(new java.awt.Color(0, 0, 0, 90));
        if (xIn > area.x)
            g.fillRect(area.x, area.y, Math.min(xIn, area.x + area.width) - area.x, area.height);
        if (xOut < area.x + area.width)
            g.fillRect(Math.max(xOut, area.x), area.y, area.x + area.width - Math.max(xOut, area.x), area.height);

        g.setColor(UIGlobals.TL_MOVIE_FRAME_COLOR);
        java.awt.Stroke saved = g.getStroke();
        g.setStroke(new java.awt.BasicStroke(1.5f));
        for (int x : new int[]{xIn, xOut}) {
            if (x >= area.x && x <= area.x + area.width) {
                g.drawLine(x, 0, x, h);
                g.fillPolygon(new int[]{x - 4, x + 4, x}, new int[]{area.y, area.y, area.y + 6}, 3); // handle
            }
        }
        g.setStroke(saved);
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        Point p = e.getPoint();
        if (e.getClickCount() == 2) {
            DrawController.resetAxis(p);
            return;
        }

        ClickableDrawable element = TimelineLayers.getDrawableUnderMouse();
        if (element != null) {
            element.clicked(e.getLocationOnScreen(), DrawController.getGeometry().xMapper(DrawController.selectedAxis).toValue(p.x));
        } else {
            DrawController.setMovieFrame(p);
        }
    }

    @Override
    public void mouseEntered(MouseEvent e) {}

    @Override
    public void mouseExited(MouseEvent e) {
        JHVEventCache.highlight(null);
        mousePosition = null;
        if (TimelineLayers.setYAxisHighlight(null)) {
            drawRequest();
            return;
        }
        repaint();
    }

    @Override
    public void mousePressed(MouseEvent e) {
        Point p = e.getPoint();
        mousePressedPosition = p;
        if (e.isAltDown()) { // Option-drag trims the movie, like the top scrubber's ends
            dragMode = DragMode.TRIM;
            trimDraggingEnd = nearerToTrimEnd(p.x);
            setTrimAt(p.x);
        } else if (overMovieLine(p)) {
            // setCursor(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR));
            dragMode = DragMode.MOVIELINE;
        } else {
            setCursor(UIGlobals.closedHandCursor);
            dragMode = DragMode.CHART;
        }
    }

    // True if x is nearer the current out-point than the in-point.
    private static boolean nearerToTrimEnd(int x) {
        java.awt.Rectangle area = DrawController.getGeometry().area();
        org.helioviewer.jhv.timelines.draw.TimeAxis.Mapper m = DrawController.selectedAxis.mapper(area.x, area.width);
        int xIn = m.toPixel(org.helioviewer.jhv.movie.Player.getPlaybackFirstTime());
        int xOut = m.toPixel(org.helioviewer.jhv.movie.Player.getPlaybackLastTime());
        return Math.abs(x - xOut) <= Math.abs(x - xIn);
    }

    // Set the trim in/out (whichever this drag owns) to the frame nearest the cursor time.
    private void setTrimAt(int x) {
        java.awt.Rectangle area = DrawController.getGeometry().area();
        org.helioviewer.jhv.timelines.draw.TimeAxis.Mapper m = DrawController.selectedAxis.mapper(area.x, area.width);
        long t = m.toValue(Math.clamp(x, area.x, area.x + area.width));
        int frame = org.helioviewer.jhv.movie.Player.frameForTime(t);
        org.helioviewer.jhv.app.state.ViewState.PlaybackData d = org.helioviewer.jhv.app.state.ViewState.playbackData();
        if (trimDraggingEnd)
            org.helioviewer.jhv.app.Commands.setPlaybackRange(d.firstFrame(), Math.max(frame, d.firstFrame()));
        else
            org.helioviewer.jhv.app.Commands.setPlaybackRange(Math.min(frame, d.lastFrame()), d.lastFrame());
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        Point p = e.getPoint();

        switch (dragMode) {
            case CHART -> {
                setCursor(UIGlobals.openHandCursor);
                if (mousePressedPosition != null && chartDragged) {
                    DrawController.moveX(mousePressedPosition.x - p.x);
                    DrawController.moveAllAxes(p.y - mousePressedPosition.y);
                }
            }
            case MOVIELINE -> DrawController.setMovieFrame(p);
            case TRIM -> setTrimAt(p.x);
            case NODRAG -> {}
        }
        dragMode = DragMode.NODRAG;
        mousePressedPosition = null;
        chartDragged = false;
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        Point p = e.getPoint();
        if (mousePressedPosition != null) {
            switch (dragMode) {
                case CHART -> {
                    chartDragged = true;
                    setCursor(UIGlobals.closedHandCursor);
                    DrawController.moveX(mousePressedPosition.x - p.x);
                    DrawController.moveY(p, p.y - mousePressedPosition.y);
                }
                case MOVIELINE -> DrawController.setMovieFrame(p);
                case TRIM -> setTrimAt(p.x);
                case NODRAG -> {}
            }
        }
        mousePressedPosition = p;
    }

    private static boolean overMovieLine(Point p) {
        int movieLinePosition = DrawController.getMovieLinePosition();
        return movieLinePosition >= 0 && movieLinePosition - 3 <= p.x && p.x <= movieLinePosition + 3;
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        mousePosition = e.getPoint();

        GraphGeometry geometry = DrawController.getGeometry();
        if (e.isAltDown()) {
            // Option held anywhere over the timeline: crop cursor, matching the top scrubber — the
            // same trim gesture (Option-drag) works here.
            setCursor(org.helioviewer.jhv.gui.component.TrimCursor.get());
        } else if (overMovieLine(mousePosition)) {
            setCursor(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR));
        } else if (TimelineLayers.getDrawableUnderMouse() != null) {
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        } else if (geometry.area().contains(mousePosition)) {
            setCursor(UIGlobals.openHandCursor);
        } else {
            setCursor(Cursor.getDefaultCursor());
        }

        boolean axisHighlightChanged = TimelineLayers.setYAxisHighlight(geometry.yAxisHit(mousePosition));
        boolean eventHighlightChanged = TimelineLayers.highlightChanged(mousePosition);
        if (axisHighlightChanged || eventHighlightChanged) {
            drawRequest();
        } else {
            repaint(); // for timeline values
        }
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        if (e.getScrollType() == MouseWheelEvent.WHEEL_UNIT_SCROLL) {
            int scrollDistance = e.getWheelRotation() * e.getScrollAmount();
            DrawController.zoomXY(e.getPoint(), scrollDistance, e.isShiftDown(), e.isAltDown(), e.isControlDown());
        }
    }

    @Override
    public void componentHidden(ComponentEvent e) {}

    @Override
    public void componentMoved(ComponentEvent e) {}

    @Override
    public void componentResized(ComponentEvent e) {
        DrawController.setGraphSize(new Rectangle(getWidth(), getHeight()));
        if (mousePosition != null)
            TimelineLayers.setYAxisHighlight(DrawController.getGeometry().yAxisHit(mousePosition));
    }

    @Override
    public void componentShown(ComponentEvent e) {}

    @Override
    public void drawRequest() {
        redrawGraphArea = true;
        repaint();
    }

    @Override
    public void drawMovieLineRequest() {
        repaint();
    }

}
