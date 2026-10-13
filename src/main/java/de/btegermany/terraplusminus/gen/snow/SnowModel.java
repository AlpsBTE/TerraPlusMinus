package de.btegermany.terraplusminus.gen.snow;

//TODO: move to T--
/**
 * Heuristic model to decide whether a column should have snow or not
 */
public final class SnowModel {

    private final int minHeight;
    private final double aspectBonus;
    private final double thetaMin;
    private final double thetaRange;
    private final double hMid;
    private final double k;

    public SnowModel(int minHeight, double aspectBonus,
                     double thetaMin, double thetaRange,
                     double hMid, double k) {
        this.minHeight = minHeight;
        this.aspectBonus = aspectBonus;
        this.thetaMin = thetaMin;
        this.thetaRange = thetaRange;
        this.hMid = hMid;
        this.k = k;
    }

    public static SnowModel defaults() {
        return new SnowModel(2800, 250.0d, 15.0d, 55.0d, 3300.0d, 0.0035d);
    }

    public int minHeight() {
        return this.minHeight;
    }

    public double aspectBonus() {
        return this.aspectBonus;
    }

    public double slopeMin() {
        return this.thetaMin;
    }

    public double slopeRange() {
        return this.thetaRange;
    }

    public double heightMid() {
        return this.hMid;
    }

    public double slopeSteepness() {
        return this.k;
    }

    public boolean hasSnow(int height, double slopeAngle, double azimuth, boolean southernHemisphere) {
        if (height < this.minHeight) return false;
        double effectiveHeight = height + aspectAdjustment(azimuth, southernHemisphere);
        double maxSlope = maxSlope(effectiveHeight);
        return slopeAngle <= maxSlope;
    }

    public static int frostLayers(double residual) {
        int layers = (int) Math.floor((residual + 0.5d) * 8.0d) + 1;
        return Math.clamp(layers, 1, 8);
    }

    public static int edgeRamp(int layers, int eligibleNeighbors) {
        return Math.clamp(layers, 1, eligibleNeighbors - 1);
    }

    private double aspectAdjustment(double azimuth, boolean southernHemisphere) {
        if (Double.isNaN(azimuth)) return 0.0d;
        double bonus = this.aspectBonus * Math.cos(Math.toRadians(azimuth));
        return southernHemisphere ? -bonus : bonus;
    }

    private double maxSlope(double effectiveHeight) {
        return this.thetaMin + this.thetaRange / (1.0d + Math.exp(-this.k * (effectiveHeight - this.hMid)));
    }
}
