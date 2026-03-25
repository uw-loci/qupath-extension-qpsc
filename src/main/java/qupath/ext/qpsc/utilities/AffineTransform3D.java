package qupath.ext.qpsc.utilities;

import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.util.Arrays;

/**
 * 3D affine transform using a 4x4 homogeneous matrix.
 *
 * <p>Extends the concept of Java's 2D {@link AffineTransform} to three dimensions.
 * For current QPSC use, XY alignment is handled by 2D transforms and Z is independent
 * (no XY-Z coupling). This class supports that common case via the
 * {@link #from2D(AffineTransform, double, double)} factory, while also providing
 * a general 4x4 matrix for future 3D registration needs.
 *
 * <p>The matrix layout (row-major):
 * <pre>
 * | m00 m01 m02 tx |   | x |   | x' |
 * | m10 m11 m12 ty | * | y | = | y' |
 * | m20 m21 m22 tz |   | z |   | z' |
 * |  0   0   0   1 |   | 1 |   | 1  |
 * </pre>
 *
 * @author Mike Nelson
 * @since 4.1
 */
public class AffineTransform3D {

    // 4x4 matrix stored row-major as flat array [m00, m01, m02, tx, m10, m11, m12, ty, ...]
    private final double[] matrix;

    /**
     * Creates a 3D affine transform from an explicit 4x4 matrix (row-major, 16 elements).
     * The last row must be [0, 0, 0, 1].
     */
    public AffineTransform3D(double[] matrix4x4) {
        if (matrix4x4.length != 16) {
            throw new IllegalArgumentException("Matrix must have 16 elements (4x4), got " + matrix4x4.length);
        }
        this.matrix = Arrays.copyOf(matrix4x4, 16);
    }

    /**
     * Creates a 3D identity transform.
     */
    public AffineTransform3D() {
        this.matrix = new double[] {
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1
        };
    }

    /**
     * Promotes a 2D AffineTransform to 3D with independent Z scale and offset.
     *
     * <p>This is the primary factory for current QPSC use: XY alignment via 2D affine,
     * Z via separate scale + offset. The resulting 3D transform applies the 2D transform
     * in XY and the Z scale+offset independently.
     *
     * @param transform2d The 2D XY affine transform
     * @param zScale Z scale factor (1.0 = no scaling, i.e. Z in same units as input)
     * @param zOffset Z translation offset (added after scaling)
     * @return A 3D affine transform with decoupled XY and Z
     */
    public static AffineTransform3D from2D(AffineTransform transform2d, double zScale, double zOffset) {
        double[] m = new double[16];
        // XY block from 2D transform
        m[0] = transform2d.getScaleX(); // m00
        m[1] = transform2d.getShearX(); // m01
        m[3] = transform2d.getTranslateX(); // tx
        m[4] = transform2d.getShearY(); // m10
        m[5] = transform2d.getScaleY(); // m11
        m[7] = transform2d.getTranslateY(); // ty
        // Z block
        m[10] = zScale; // m22
        m[11] = zOffset; // tz
        // Homogeneous row
        m[15] = 1;
        return new AffineTransform3D(m);
    }

    /**
     * Promotes a 2D AffineTransform to 3D with Z pass-through (scale=1, offset=0).
     */
    public static AffineTransform3D from2D(AffineTransform transform2d) {
        return from2D(transform2d, 1.0, 0.0);
    }

    /**
     * Transforms a 3D point [x, y, z].
     *
     * @param xyz Input point [x, y, z]
     * @return Transformed point [x', y', z']
     */
    public double[] transform(double[] xyz) {
        if (xyz.length < 3) {
            throw new IllegalArgumentException("Input must have at least 3 elements, got " + xyz.length);
        }
        double x = xyz[0], y = xyz[1], z = xyz[2];
        return new double[] {
            matrix[0] * x + matrix[1] * y + matrix[2] * z + matrix[3],
            matrix[4] * x + matrix[5] * y + matrix[6] * z + matrix[7],
            matrix[8] * x + matrix[9] * y + matrix[10] * z + matrix[11]
        };
    }

    /**
     * Transforms X, Y, Z coordinates.
     */
    public double[] transform(double x, double y, double z) {
        return transform(new double[] {x, y, z});
    }

    /**
     * Extracts the 2D XY plane transform (ignoring Z).
     *
     * @return A 2D AffineTransform representing the XY projection
     */
    public AffineTransform to2D() {
        return new AffineTransform(
                matrix[0], // m00 = scaleX
                matrix[4], // m10 = shearY
                matrix[1], // m01 = shearX
                matrix[5], // m11 = scaleY
                matrix[3], // m02 = translateX
                matrix[7] // m12 = translateY
                );
    }

    /** Z scale factor (m22). */
    public double getScaleZ() {
        return matrix[10];
    }

    /** Z translation offset (tz). */
    public double getTranslateZ() {
        return matrix[11];
    }

    /** XY scale X (m00). */
    public double getScaleX() {
        return matrix[0];
    }

    /** XY scale Y (m11). */
    public double getScaleY() {
        return matrix[5];
    }

    /**
     * Returns true when Z is pass-through (scale=1, offset=0, no XY-Z coupling).
     * In this case, the transform is effectively 2D and {@link #to2D()} is lossless.
     */
    public boolean isEffectively2D() {
        return matrix[10] == 1.0
                && matrix[11] == 0.0
                && matrix[2] == 0.0
                && matrix[6] == 0.0 // no XY->Z coupling
                && matrix[8] == 0.0
                && matrix[9] == 0.0; // no Z->XY coupling
    }

    /**
     * Creates the inverse of this 3D transform.
     *
     * <p>For the common case of decoupled XY and Z (no cross-coupling), this inverts
     * the 2D XY block and Z independently for better numerical stability.
     *
     * @return The inverse transform
     * @throws ArithmeticException if the matrix is singular
     */
    public AffineTransform3D createInverse() {
        if (isEffectively2D()) {
            // Fast path: invert XY and Z independently
            try {
                AffineTransform inv2d = to2D().createInverse();
                if (matrix[10] == 0.0) {
                    throw new ArithmeticException("Cannot invert 3D transform: Z scale is zero");
                }
                double invZScale = 1.0 / matrix[10];
                double invZOffset = -matrix[11] / matrix[10];
                return from2D(inv2d, invZScale, invZOffset);
            } catch (NoninvertibleTransformException e) {
                throw new ArithmeticException("2D transform is not invertible: " + e.getMessage());
            }
        }
        // General 4x4 inversion would go here for future use
        throw new UnsupportedOperationException("General 4x4 matrix inversion not yet implemented. "
                + "Use decoupled XY+Z transforms (from2D factory) for current workflows.");
    }

    /**
     * Returns the raw 4x4 matrix as a 16-element row-major array.
     */
    public double[] getMatrix() {
        return Arrays.copyOf(matrix, 16);
    }

    @Override
    public String toString() {
        if (isEffectively2D()) {
            return String.format(
                    "AffineTransform3D[2D: scaleX=%.6f, scaleY=%.6f, tx=%.3f, ty=%.3f, Z=passthrough]",
                    matrix[0], matrix[5], matrix[3], matrix[7]);
        }
        return String.format(
                "AffineTransform3D[scaleX=%.6f, scaleY=%.6f, scaleZ=%.6f, tx=%.3f, ty=%.3f, tz=%.3f]",
                matrix[0], matrix[5], matrix[10], matrix[3], matrix[7], matrix[11]);
    }
}
