package com.legoj15.megablockbot.core;

/** A block position as plain ints. */
public record Pos(int x, int y, int z) {
    public Pos offset(int dx, int dy, int dz) {
        return new Pos(x + dx, y + dy, z + dz);
    }

    public long distSq(Pos o) {
        long dx = x - o.x, dy = y - o.y, dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Horizontal distance squared; what "within radius" means for travel targets. */
    public long distSqXZ(Pos o) {
        long dx = x - o.x, dz = z - o.z;
        return dx * dx + dz * dz;
    }

    /** {@code "x,y,z"}; the inverse of {@link #parse}. Used in node params and checkpoints. */
    public String encode() {
        return x + "," + y + "," + z;
    }

    public static Pos parse(String s) {
        String[] p = s.split(",");
        if (p.length != 3) throw new IllegalArgumentException("not a position: " + s);
        return new Pos(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
    }
}
