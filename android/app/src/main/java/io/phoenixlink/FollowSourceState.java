package io.phoenixlink;

// One generation per source/visibility change prevents late output from crossing apps.
final class FollowSourceState {
    static final class Snapshot {
        final int window,left,top,right,bottom; final long generation; final boolean allowed;
        Snapshot(int id,int l,int t,int r,int b,long g,boolean a) {window=id;left=l;top=t;right=r;bottom=b;generation=g;allowed=a;}
    }
    private volatile Snapshot value=new Snapshot(-1,0,0,0,0,0,false);
    Snapshot get() {return value;}
    synchronized Snapshot select(int id,int l,int t,int r,int b) {
        if(id<0 || r<=l || b<=t)throw new IllegalArgumentException("Invalid window bounds");
        Snapshot s=value;
        if(s.window!=id || s.left!=l || s.top!=t || s.right!=r || s.bottom!=b) value=new Snapshot(id,l,t,r,b,s.generation+1,false);
        return value;
    }
    synchronized void block(boolean clear) {
        Snapshot s=value;
        if(clear) { if(s.window!=-1)value=new Snapshot(-1,0,0,0,0,s.generation+1,false); }
        else value=new Snapshot(s.window,s.left,s.top,s.right,s.bottom,s.generation+1,false);
    }
    synchronized boolean permit(long generation) {
        Snapshot s=value;if(s.window<0 || s.generation!=generation)return false;
        if(!s.allowed)value=new Snapshot(s.window,s.left,s.top,s.right,s.bottom,s.generation,true);
        return true;
    }
    boolean accepts(long generation) {Snapshot s=value;return s.window>=0 && s.allowed && s.generation==generation;}
}
