package io.phoenixlink;

final class FrameGeometry {
    static int[] scaled(int width,int height,int edge) {
        if(width<1 || height<1 || edge<1) throw new IllegalArgumentException("Positive dimensions required.");
        double scale=Math.min(1.0,(double)edge/Math.max(width,height));
        return new int[] { Math.max(1,(int)Math.round(width*scale)),Math.max(1,(int)Math.round(height*scale)) };
    }
}
