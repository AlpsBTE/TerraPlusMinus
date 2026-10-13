package de.btegermany.terraplusminus.gen.landcover;

import net.buildtheearth.terraminusminus.projection.OutOfProjectionBoundsException;
import net.buildtheearth.terraminusminus.util.CornerBoundingBox2d;
import net.buildtheearth.terraminusminus.util.bvh.BVH;
import org.jspecify.annotations.NonNull;

import java.util.concurrent.CompletableFuture;

//TODO: move to T--
public interface LandCoverDataset {

    /**
     * Gets all polygons that may intersect the given bounds
     */
    CompletableFuture<BVH<LandCoverPolygon> @NonNull []> getAsync(@NonNull CornerBoundingBox2d boundsGeo) throws OutOfProjectionBoundsException;
}
