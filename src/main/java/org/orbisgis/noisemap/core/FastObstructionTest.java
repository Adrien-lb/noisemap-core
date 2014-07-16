/**
 * NoiseMap is a scientific computation plugin for OrbisGIS developed in order to
 * evaluate the noise impact on urban mobility plans. This model is
 * based on the French standard method NMPB2008. It includes traffic-to-noise
 * sources evaluation and sound propagation processing.
 *
 * This version is developed at French IRSTV Institute and at IFSTTAR
 * (http://www.ifsttar.fr/) as part of the Eval-PDU project, funded by the
 * French Agence Nationale de la Recherche (ANR) under contract ANR-08-VILL-0005-01.
 *
 * Noisemap is distributed under GPL 3 license. Its reference contact is Judicaël
 * Picaut <judicael.picaut@ifsttar.fr>. It is maintained by Nicolas Fortin
 * as part of the "Atelier SIG" team of the IRSTV Institute <http://www.irstv.fr/>.
 *
 * Copyright (C) 2011 IFSTTAR
 * Copyright (C) 2011-2012 IRSTV (FR CNRS 2488)
 *
 * Noisemap is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * Noisemap is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * Noisemap. If not, see <http://www.gnu.org/licenses/>.
 *
 * For more information, please consult: <http://www.orbisgis.org/>
 * or contact directly:
 * info_at_ orbisgis.org
 */
package org.orbisgis.noisemap.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Stack;

import com.vividsolutions.jts.algorithm.Angle;
import com.vividsolutions.jts.math.Vector2D;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.LineSegment;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.geom.Polygon;

/**
 * FastObstructionTest speed up the search of
 * visibility test and get the 3D diffraction data. TODO  using income data to do something called
 * visibility culling.
 *
 * @author Nicolas Fortin
 * @author SU Qi
 */
public class FastObstructionTest {
    public static final double epsilon = 1e-7;
    public static final double wideAngleTranslationEpsilon = 0.01;
    public static final double receiverDefaultHeight = 1.6;
    private long nbObstructionTest = 0;
    private List<Triangle> triVertices;
    private List<Coordinate> vertices;
    private List<Triangle> triNeighbors; // Neighbors
    private List<MeshBuilder.PolygonWithHeight> polygonWithHeight = new ArrayList<MeshBuilder.PolygonWithHeight>();//list polygon with height

    private QueryGeometryStructure triIndex = null; //TODO remove
    private int lastFountPointTriTest = 0;
    private List<Float> verticesOpenAngle = null;
    private List<Coordinate> verticesOpenAngleTranslated = null; /*Open angle*/
    private boolean hasBuildingWithHeight;
    //private LinkedList<Integer> BuildingTriangleIndex= new LinkedList<Integer>(); /* the buildings list between source and receiver. Reconstruction after get a new source-reciver */
    //private LinkedList<Coordinate> intersections= new LinkedList<Coordinate>();/* the intersection of the segment source-receiver and builiding's side. Reconstruction after get a new source-reciver */


    //data for calculate 3D diffraction,
    //first Coordinate is the coordinate after the changement coordinate system, the second parameter will keep the data of orignal coordinate system

    public final int Delta_Distance = 0;//delta distance;
    public final int E_Length = 1;//e length
    public final int Full_Diffraction_Distance = 2;//the full distance of difrraction path
    public final int SrcReceiverDistance = 0;

    /**
     * New constructor, initialize buildings, triangles and points from mesh data
     *
     * @param buildings    list of buildings with their height
     * @param triangles    list of triangles including buildingID which correspondent the building e.x: triangle.buildingID=1 <=> buildings[0]
     *                     If a triangle is not in building, buildingID for this triangle is 0
     * @param triNeighbors list of neighbors triangle
     * @param points       list of all points in mesh, this points includes vertices of building, Topograhic points, vertices of boundingBox
     */
    public FastObstructionTest(LinkedList<MeshBuilder.PolygonWithHeight> buildings,
                               List<Triangle> triangles, List<Triangle> triNeighbors, List<Coordinate> points) {

        List<MeshBuilder.PolygonWithHeight> polygonWithHeightArray = new ArrayList<MeshBuilder.PolygonWithHeight>(buildings);
        hasBuildingWithHeight = false;
        for(MeshBuilder.PolygonWithHeight poly : polygonWithHeightArray) {
            if(poly.hasHeight()) {
                hasBuildingWithHeight = true;
                break;
            }
        }
        GeometryFactory factory = new GeometryFactory();
        this.polygonWithHeight = polygonWithHeightArray;
        this.triVertices = triangles;
        this.triNeighbors = triNeighbors;
        this.vertices = points;

        // /////////////////////////////////
        // Feed Query Structure to find triangle, by coordinate

        triIndex = new QueryQuadTree();
        int triind = 0;
        for (Triangle tri : this.triVertices) {
            final Coordinate[] triCoords = {vertices.get(tri.getA()),
                    vertices.get(tri.getB()), vertices.get(tri.getC()),
                    vertices.get(tri.getA())};
            Polygon newpoly = factory.createPolygon(
                    factory.createLinearRing(triCoords), null);
            triIndex.appendGeometry(newpoly, triind);
            triind++;
        }
        //give a average height to each building
        setAverageBuildingHeight(this.polygonWithHeight);
    }


    public long getNbObstructionTest() {
        return nbObstructionTest;
    }

    /**
     * Retrieve triangle list, only for debug and unit test purpose
     *
     * @return Triangle list
     */
    public List<Triangle> getTriangles() {
        return triVertices;
    }


    /**
     * @return vertices list, only for debug and unit test purpose
     */
    public List<Coordinate> getVertices() {
        return vertices;
    }


    /**
     * Compute the next triangle index.Find the shortest intersection point of
     * triIndex segments to the p1 coordinate
     *
     * @param triIndex        Triangle index
     * @param propagationLine Propagation line
     * @return Next triangle to the specified direction, -1 if there is no
     * triangle neighbor.
     */
    private int getNextTri(final int triIndex,
                           final LineSegment propagationLine,
                           HashSet<Integer> navigationHistory) {
            //NonRobustLineIntersector linters = new NonRobustLineIntersector();
            final Triangle tri = this.triVertices.get(triIndex);
        int nearestIntersectionSide = -1;
        int idneigh;

        double nearestIntersectionPtDist = Double.MAX_VALUE;
        // Find intersection pt
        final Coordinate aTri = this.vertices.get(tri.getA());
        final Coordinate bTri = this.vertices.get(tri.getB());
        final Coordinate cTri = this.vertices.get(tri.getC());
        double distline_line;
        Coordinate intersection = new Coordinate();
        //if there is no intersection, by default we set the - max value to topointersection to avoid the problem
        double zTopoIntersection = -Double.MAX_VALUE;
        double zRandSIntersection;
        // Intersection First Side
        idneigh = this.triNeighbors.get(
                triIndex).get(2);
        //add: search triangle without height
        if (idneigh != -1 && !navigationHistory.contains(idneigh)) {
            distline_line = propagationLine.distance(new LineSegment(aTri, bTri));
            if (distline_line < FastObstructionTest.epsilon && // Todo condition
                    distline_line < nearestIntersectionPtDist && this.triVertices.get(idneigh).getBuidlingID() == 0) {
                nearestIntersectionPtDist = distline_line;
                nearestIntersectionSide = 2;
                //we will get the intersection point coordinate with(x,y,NaN)
                if (!(propagationLine.intersection(new LineSegment(aTri, bTri)) == null)) {
                    intersection = new Coordinate(propagationLine.intersection(new LineSegment(aTri, bTri)));
                    //get this point Z using interseted segment.
                    zTopoIntersection = calculateLinearInterpolation(aTri, bTri, intersection);
                }
            }
        }
        // Intersection Second Side
        idneigh = this.triNeighbors.get(
                triIndex).get(0);
        if (idneigh != -1 && !navigationHistory.contains(idneigh)) {
            distline_line = propagationLine.distance(new LineSegment(bTri, cTri));
            if (distline_line < FastObstructionTest.epsilon &&
                    distline_line < nearestIntersectionPtDist && this.triVertices.get(idneigh).getBuidlingID() == 0) {
                nearestIntersectionPtDist = distline_line;
                nearestIntersectionSide = 0;
                if (!(propagationLine.intersection(new LineSegment(bTri, cTri)) == null)) {
                    intersection = new Coordinate(propagationLine.intersection(new LineSegment(bTri, cTri)));
                    //get this point Z using interseted segment.
                    zTopoIntersection = calculateLinearInterpolation(bTri, cTri, intersection);
                }


            }
        }

        // Intersection Third Side
        idneigh = this.triNeighbors.get(
                triIndex).get(1);
        if (idneigh != -1 && !navigationHistory.contains(idneigh)) {
            distline_line = propagationLine.distance(new LineSegment(cTri, aTri));
            if (distline_line < FastObstructionTest.epsilon &&
                    distline_line < nearestIntersectionPtDist && this.triVertices.get(idneigh).getBuidlingID() == 0) {
                nearestIntersectionSide = 1;
                if (!(propagationLine.intersection(new LineSegment(cTri, aTri)) == null)) {
                    intersection = new Coordinate(propagationLine.intersection(new LineSegment(cTri, aTri)));
                    //get this point Z using interseted line.

                    zTopoIntersection = calculateLinearInterpolation(cTri, aTri, intersection);
                }
            }
        }
        if (nearestIntersectionSide != -1) {
            //get this point Z using propagation line
            zRandSIntersection = calculateLinearInterpolation(propagationLine.p0, propagationLine.p1, intersection);
            //If the Z calculated by propagation Line >= Z calculated by intersected line, we will find next triangle
            if (zRandSIntersection >= zTopoIntersection) {
                return this.triNeighbors.get(triIndex).get(nearestIntersectionSide);
            }
            //Else, the Z of Topo intersection > Z calculated by propagation Line, the Topo intersection will block the propagation line
            else {
                //Propagation line blocked by the topography
                return -1;
            }
        } else {
            return -1;
        }
    }

    /**
     * Compute the next triangle index.Find the shortest intersection point of
     * triIndex segments to the p1 coordinate and add the triangles in building to the list
     * and add the point of intersection(between segment of source-reciver and segment of triangle) to the list
     *
     * @param triIndex        Triangle index
     * @param propagationLine Propagation line
     */
    private TriIdWithIntersection getNextTriWithIntersection(final int triIndex,
                                                             final LineSegment propagationLine,
                                                             HashSet<Integer> navigationHistory) {
        //NonRobustLineIntersector linters = new NonRobustLineIntersector();
        final Triangle tri = this.triVertices.get(triIndex);
        int nearestIntersectionSide = -1;
        int idneigh;
        double nearestIntersectionPtDist = Double.MAX_VALUE;
        // Find intersection pt
        final Coordinate aTri = this.vertices.get(tri.getA());
        final Coordinate bTri = this.vertices.get(tri.getB());
        final Coordinate cTri = this.vertices.get(tri.getC());
        double distline_line;
        // Intersection First Side
        idneigh = this.triNeighbors.get(
                triIndex).get(2);
        Coordinate intersection = new Coordinate();
        if (idneigh != -1 && !navigationHistory.contains(idneigh)) {
            distline_line = propagationLine.distance(new LineSegment(aTri, bTri));
            if (distline_line < FastObstructionTest.epsilon &&
                    distline_line < nearestIntersectionPtDist) {
                nearestIntersectionPtDist = distline_line;
                nearestIntersectionSide = 2;
                if (!(propagationLine.intersection(new LineSegment(aTri, bTri)) == null)) {
                    intersection = new Coordinate(propagationLine.intersection(new LineSegment(aTri, bTri)));
                }
            }
        }
        // Intersection Second Side
        idneigh = this.triNeighbors.get(
                triIndex).get(0);
        if (idneigh != -1 && !navigationHistory.contains(idneigh)) {
            distline_line = propagationLine.distance(new LineSegment(bTri, cTri));
            if (distline_line < FastObstructionTest.epsilon &&
                    distline_line < nearestIntersectionPtDist) {
                nearestIntersectionPtDist = distline_line;
                nearestIntersectionSide = 0;
                if (!(propagationLine.intersection(new LineSegment(bTri, cTri)) == null)) {
                    intersection = new Coordinate(propagationLine.intersection(new LineSegment(bTri, cTri)));
                }
            }
        }

        // Intersection Third Side
        idneigh = this.triNeighbors.get(
                triIndex).get(1);
        if (idneigh != -1 && !navigationHistory.contains(idneigh)) {
            distline_line = propagationLine.distance(new LineSegment(cTri, aTri));
            if (distline_line < FastObstructionTest.epsilon &&
                    distline_line < nearestIntersectionPtDist) {
                nearestIntersectionSide = 1;
                if (!(propagationLine.intersection(new LineSegment(cTri, aTri)) == null)) {
                    intersection = new Coordinate(propagationLine.intersection(new LineSegment(cTri, aTri)));
                }
            }
        }

        //these points will be used by calculate Linear interpolation
        Coordinate p1 = new Coordinate();
        Coordinate p2 = new Coordinate();

        switch (nearestIntersectionSide) {
            case 0: {
                p1 = bTri;
                p2 = cTri;
                break;
            }
            case 1: {
                p1 = cTri;
                p2 = aTri;
                break;
            }
            case 2: {
                p1 = aTri;
                p2 = bTri;
                break;
            }
        }
        int BuildingNextTriID = this.triNeighbors.get(triIndex).get(nearestIntersectionSide);
        boolean triNeighborIsBuidling = false;//check if the point is the intersection of triangle In the same building
        boolean intersectionPointOnBuilding = false;//check if the intersection point is On the building
        double nextTriHeight = 0.;

        if (this.triVertices.get(BuildingNextTriID).getBuidlingID() > 0) {
            nextTriHeight = this.polygonWithHeight.get(this.triVertices.get(BuildingNextTriID).getBuidlingID() - 1).getHeight();
        }

        if (tri.getBuidlingID() > 0 && (nextTriHeight > 0)) {
            //intersection is between two triangle in the same building, so we will not keep intersection point
            triNeighborIsBuidling = true;
        }
        //add height to this intersection
        if (tri.getBuidlingID() == 0 && nextTriHeight > 0) {
            intersectionPointOnBuilding = true;
            intersection.setOrdinate(2, nextTriHeight);
        }

                /*
                //not useful for the Max function because we merged all buildings, 
                //so we dont have intersection between the two differents buildings and the building height will be the same in a building
                else if(tri.getBuidlingID()>0&&nextTriHeight>0){
                    intersection.z=nextTriHeight;
                            //Math.max(nextTriHeight, this.polygonWithHeight.get(tri.getBuidlingID()-1).getHeight());
                }
                */
        else if (tri.getBuidlingID() > 0 && Double.compare(nextTriHeight, 0.) == 0) {
            intersection.setOrdinate(2, this.polygonWithHeight.get(tri.getBuidlingID() - 1).getHeight());
            intersectionPointOnBuilding = true;
        }
        //if in these two triangles we have no building
        else if (tri.getBuidlingID() == 0 && Double.compare(nextTriHeight, 0.) == 0) {
            intersection.setOrdinate(2, calculateLinearInterpolation(p1, p2, intersection));
        }

        if (nearestIntersectionSide != -1) {

            //if intersection is not the intersection in the same building, save this intersection to intersection list
            if (!triNeighborIsBuidling && !propagationLine.p0.equals(intersection) && !propagationLine.p1.equals(intersection)) {

                //Buidling whcih is between ray source-receiver have 2 intersections,
                //If the intersection is corner of the buiding, intersections will save 2 times with the same value
                return new TriIdWithIntersection(BuildingNextTriID, intersection, intersectionPointOnBuilding);
            } else {
                return new TriIdWithIntersection(BuildingNextTriID, new Coordinate(-1, -1, -1), intersectionPointOnBuilding);
            }

        } else {
            return new TriIdWithIntersection(-1, new Coordinate(-1, -1, -1), intersectionPointOnBuilding);
        }
    }

    /**
     * Fast dot in triangle test
     * <p/>
     * {@see http://www.blackpawn.com/texts/pointinpoly/default.html}
     *
     * @param p Coordinate of the point
     * @param a Coordinate of the A vertex of triangle
     * @param b Coordinate of the B vertex of triangle
     * @param c Coordinate of the C vertex of triangle
     * @return True if dot is in triangle
     */
    private boolean dotInTri(Coordinate p, Coordinate a, Coordinate b,
                             Coordinate c) {
        Vector2D v0 = new Vector2D(c.x - a.x, c.y - a.y);
        Vector2D v1 = new Vector2D(b.x - a.x, b.y - a.y);
        Vector2D v2 = new Vector2D(p.x - a.x, p.y - a.y);

        // Compute dot products
        double dot00 = v0.dot(v0);
        double dot01 = v0.dot(v1);
        double dot02 = v0.dot(v2);
        double dot11 = v1.dot(v1);
        double dot12 = v1.dot(v2);

        // Compute barycentric coordinates
        double invDenom = 1 / (dot00 * dot11 - dot01 * dot01);
        double u = (dot11 * dot02 - dot01 * dot12) * invDenom;
        double v = (dot00 * dot12 - dot01 * dot02) * invDenom;

        // Check if point is in triangle
        return (u > (0. - epsilon)) && (v > (0. - epsilon))
                && (u + v < (1. + epsilon));

    }

    Coordinate[] getTriangle(int triIndex) {
        final Triangle tri = this.triVertices.get(triIndex);
        return new Coordinate[]{this.vertices.get(tri.getA()),
                this.vertices.get(tri.getB()), this.vertices.get(tri.getC())};
    }

    /**
     * Return the triangle id from a point coordinate inside the triangle
     *
     * @param pt Point test
     * @return Triangle Id, Or -1 if no triangle has been found
     */

    private int getTriangleIdByCoordinate(Coordinate pt) {
        // Shortcut, test if the last found triangle contain this point, if not
        // use the quadtree
        Coordinate[] trit = getTriangle(lastFountPointTriTest);
        if (dotInTri(pt, trit[0], trit[1], trit[2])) {
            return lastFountPointTriTest;
        }
        Envelope ptEnv = new Envelope(pt);
        Iterator<Integer> res = triIndex.query(new Envelope(ptEnv));
        while (res.hasNext()) {
            int triId = res.next();
            Coordinate[] tri = getTriangle(triId);
            if (dotInTri(pt, tri[0], tri[1], tri[2])) {
                lastFountPointTriTest = triId;
                return triId;
            }
        }
        return -1;
    }

    /**
     * Add open angle to verticesAngle array (merge with existing open angle if
     * exists)
     *
     * @param vertexId         Index of vertex in the verticesAngle array
     * @param vertexCoordinate Coordinate of the vertex
     * @param left             CCW Neighbor 1 of vertex (open angle)
     * @param right            CCW Neighbor 2 of vertex (open angle)
     * @param verticesAngle    Array of Array of open angle
     */
    public static void updateMinMax(int vertexId, Coordinate vertexCoordinate,
                                    Coordinate left, Coordinate right,
                                    ArrayList<ArrayList<Double>> verticesAngle) {
        ArrayList<Double> curVertex = verticesAngle.get(vertexId);
        Coordinate refPosition = new Coordinate(vertexCoordinate.x + 1,
                vertexCoordinate.y);
        double ccw1 = (float) Angle.angleBetweenOriented(refPosition,
                vertexCoordinate, left);
        double ccw2 = (float) Angle.angleBetweenOriented(refPosition,
                vertexCoordinate, right);
        // Iterate over the open angle array and find something ~ equal to
        // ccw1/ccw2 angle
        boolean inserted = false;
        // Update existing angle ranges
        boolean doNewLoop = true;
        while (doNewLoop) {
            doNewLoop = false;
            for (int idrange = 0; idrange < curVertex.size() - 1; idrange += 2) {
                if (curVertex.get(idrange).compareTo(ccw2) == 0) {
                    inserted = true;
                    if (curVertex.size() > 2) {
                        // Remove merged element and reloop
                        doNewLoop = true;
                        inserted = false;
                        ccw2 = curVertex.get(idrange + 1);
                        curVertex.remove(idrange);
                        curVertex.remove(idrange);
                    } else {
                        curVertex.set(idrange, ccw1);
                    }
                    break;
                } else if (curVertex.get(idrange + 1).compareTo(ccw1) == 0) {
                    inserted = true;
                    if (curVertex.size() > 2) {
                        // Remove merged element and reloop
                        doNewLoop = true;
                        inserted = false;
                        ccw1 = curVertex.get(idrange);
                        curVertex.remove(idrange);
                        curVertex.remove(idrange);
                    } else {
                        curVertex.set(idrange + 1, ccw2);
                    }
                    break;
                }
            }
        }
        // Angles not contiguous
        if (!inserted) {
            curVertex.add(ccw1);
            curVertex.add(ccw2);
        }
    }

    /**
     * @param minAngle Minimum angle [0-2Pi]
     * @param maxAngle Maximum angle [0-2Pi]
     * @return List of corners within parameters range
     */
    public List<Coordinate> getWideAnglePoints(double minAngle, double maxAngle) {
        List<Coordinate> wideAnglePts = new ArrayList<Coordinate>(
                vertices.size());
        if (verticesOpenAngle == null) {
            verticesOpenAngle = new ArrayList<Float>(vertices.size()); // Reserve
            // size
            verticesOpenAngleTranslated = new ArrayList<Coordinate>(
                    vertices.size());
            // Vertex open angle. For each vertex
            // [ccwmin,ccwmax,ccwmin,ccwmax,..]
            ArrayList<ArrayList<Double>> verticesOpenAnglesTuples = new ArrayList<ArrayList<Double>>(
                    vertices.size());
            for (int idvert = 0; idvert < vertices.size(); idvert++) {
                verticesOpenAngle.add(0.f);
                verticesOpenAnglesTuples.add(new ArrayList<Double>());
            }


            for (Triangle tri : this.triVertices) {
                if (tri.getBuidlingID() < 1) {
                    // Compute angle at each corner, then add to vertices angle
                    // array
                    Coordinate triA = vertices.get(tri.getA());
                    Coordinate triB = vertices.get(tri.getB());
                    Coordinate triC = vertices.get(tri.getC());
                    // Add A vertex min/max angle
                    updateMinMax(tri.getA(), triA, triB, triC,
                            verticesOpenAnglesTuples);
                    verticesOpenAngle.set(tri.getA(),
                            (float) (verticesOpenAngle.get(tri.getA()) + Angle
                                    .angleBetween(triB, triA, triC))
                    );
                    // Add B vertex angle
                    updateMinMax(tri.getB(), triB, triC, triA,
                            verticesOpenAnglesTuples);
                    verticesOpenAngle.set(tri.getB(),
                            (float) (verticesOpenAngle.get(tri.getB()) + Angle
                                    .angleBetween(triA, triB, triC))
                    );
                    // Add C vertex angle
                    updateMinMax(tri.getC(), triC, triA, triB,
                            verticesOpenAnglesTuples);
                    verticesOpenAngle.set(tri.getC(),
                            (float) (verticesOpenAngle.get(tri.getC()) + Angle
                                    .angleBetween(triB, triC, triA))
                    );
                }
            }

            for (int idvert = 0; idvert < vertices.size(); idvert++) {
                // Compute median angle of open angle point
                ArrayList<Double> curvert = verticesOpenAnglesTuples
                        .get(idvert);
                Coordinate curVert = vertices.get(idvert);
                if (curvert.size() == 2) {
                    double ccw1 = curvert.get(0);
                    double ccw2 = curvert.get(1);
                    if (ccw1 > ccw2) {
                        ccw1 = ccw1 - (2 * Math.PI);
                    }
                    double midAngle = ((ccw2 - ccw1) / 2.) + ccw1;
                    verticesOpenAngleTranslated.add(new Coordinate(curVert.x
                            + (Math.cos(midAngle) * wideAngleTranslationEpsilon), curVert.y
                            + (Math.sin(midAngle) * wideAngleTranslationEpsilon)));
                } else {
                    verticesOpenAngleTranslated.add(curVert);
                }
            }

        }
        int idvert = 0;
        for (Float angleVertex : verticesOpenAngle) {
            if (angleVertex >= minAngle && angleVertex <= maxAngle) {
                wideAnglePts.add(verticesOpenAngleTranslated.get(idvert));
            }
            idvert++;
        }
        return wideAnglePts;
    }

    /**
     * Compute the list of segments corresponding to holes and domain limitation
     *
     * @param maxDist Maximum distance from origin to segments
     * @param p1      Origin of search
     * @return List of segment
     */
    public LinkedList<LineSegment> getLimitsInRange(double maxDist,
                                                    Coordinate p1) {
        LinkedList<LineSegment> walls = new LinkedList<LineSegment>();
        int curTri = getTriangleIdByCoordinate(p1);
        int nextTri = -1;
        short firstSide = 0;
        HashSet<Integer> navigationHistory = new HashSet<Integer>(); // List all
        // triangles
        // already
        // processed
        Stack<Integer> navigationNodes = new Stack<Integer>(); // List the
        // current queue
        // of triangles
        // the process
        // go through
        Stack<Short> navigationSide = new Stack<Short>(); //History of current processing side

        while (curTri != -1) {
            navigationHistory.add(curTri);
            // for each side of the triangle
            Triangle neighboors = this.triNeighbors.get(curTri);
            nextTri = -1;
            for (short idside = firstSide; idside < 3; idside++) {
                if (!navigationHistory.contains(neighboors.get(idside))) {
                    IntSegment segVerticesIndex = this.triVertices.get(curTri)
                            .getSegment(idside);
                    LineSegment side = new LineSegment(
                            this.vertices.get(segVerticesIndex.getA()),
                            this.vertices.get(segVerticesIndex.getB()));
                    Coordinate closestPoint = side.closestPoint(p1);
                    if (closestPoint.distance(p1) <= maxDist) {
                        // In this direction there is a building or this is outside
                        // of the geometry
                        if (neighboors.get(idside)==-1 || triVertices.get(neighboors.get(idside)).getBuidlingID() >= 1) {
                            walls.add(side);
                        } else {
                            // Store currentTriangle Id. This is where to go
                            // back when there is no more navigable neighbors at
                            // the next triangle
                            navigationNodes.add(curTri);
                            navigationSide.add(idside);
                            firstSide = 0;
                            nextTri = neighboors.get(idside);
                            break; // Next triangle
                        }
                    }
                }
            }
            if (nextTri == -1 && !navigationNodes.empty()) {
                // All the side have been rejected, go back by one on the
                // navigation
                nextTri = navigationNodes.pop();
                firstSide = navigationSide.pop();
            }
            curTri = nextTri;
        }
        return walls;
    }

    /*
     * compute diffraction.
     */
    public boolean isFreeField(Coordinate p1, Coordinate p2) {
        nbObstructionTest++;
        LineSegment propaLine = new LineSegment(p1, p2);
        //get receiver triangle id
        int curTri = getTriangleIdByCoordinate(p1);
        //get source triangle id
        int curTriS = getTriangleIdByCoordinate(p2);
        Coordinate[] triR = getTriangle(curTri);
        Coordinate[] triS = getTriangle(curTriS);
        if (this.triVertices.get(curTri).getBuidlingID() >= 1) {
            //receiver is in the building so this propagation line is invisible
            return false;
        }
        if (this.triVertices.get(curTriS).getBuidlingID() >= 1) {
            //receiver is in the building so this propagation line is invisible
            return false;
        }

        double zTopoR = getTopoZByGiven3Points(triR[0], triR[1], triR[2], p1);
        double zTopoS = getTopoZByGiven3Points(triS[0], triS[1], triS[2], p2);

// todo
         if (zTopoR != 0) {
                //Z value of the receiver is low than topography, we will modify this receiver height
              p1.setCoordinate(new Coordinate(p1.x, p1.y, zTopoR + p1.z));
          }
          if (zTopoS != 0) {
                //Z value of the source is low than topography, we will modify this source height
              p2.setCoordinate(new Coordinate(p2.x, p2.y, zTopoS + p2.z));
          }

        if (p1.z < zTopoR || Double.isNaN(p1.z)) {
            //Z value of the receiver is low than topography, we will modify this receiver height
            p1.setCoordinate(new Coordinate(p1.x, p1.y, zTopoR + receiverDefaultHeight));
        }
        if (p2.z < zTopoS) {
            //Z value of the source is low than topography, than the propagation cant not be compute
            return false;
        }

        HashSet<Integer> navigationHistory = new HashSet<Integer>();
        if (this.triVertices.get(curTri).getBuidlingID() == 0) {
            while (curTri != -1) {
                navigationHistory.add(curTri);
                Coordinate[] tri = getTriangle(curTri);
                if (dotInTri(p2, tri[0], tri[1], tri[2])) {
                    return true;
                }
                curTri = this.getNextTri(curTri, propaLine, navigationHistory);
            }
            return false;
        } else {
            return false;
        }
    }

    /**
     * Get the distance of all intersections (after the filtration by algo Jarvis March)  between the source and the receiver to compute vertical diffraction
     * Must called after finishPolygonFeeding
     *
     * @param p1 Coordiante receiver
     * @param p2 Coordiante source
     * @return DiffractionWithSoilEffectZone
     * Double list=DiffractionWithSoilEffectZone.diffractionData : data prepared to compute diffraction
     * Double[Delta_Distance]:delta distance;
     * Double[E_Length]:e;
     * Double[Full_Diffraction_Distance]:the full distance of diffraction path
     * if Double[Delta_Distance],Double[E_Length],Double[Full_Diffraction_Distance],Double[Full_Distance_With_Soil_Effet] are -1. then no usefull intersections.
     */
    @SuppressWarnings("unchecked")
    public DiffractionWithSoilEffetZone getPath(Coordinate p1, Coordinate p2) {
        /*
        data for caculate 3D diffraction,éé
        first Coordinate is the coordinate after the changment coordinate system,
        the second parameter will keep the data of orignal coordinate system
        */

        HashMap<Coordinate, TriIdWithIntersection> newCoorInter = new HashMap<Coordinate, TriIdWithIntersection>();
        GeometryFactory factory = new GeometryFactory();
        LineString rOZone = factory.createLineString(new Coordinate[]{new Coordinate(-1, -1), new Coordinate(-1, -1)});
        LineString sOZone = factory.createLineString(new Coordinate[]{new Coordinate(-1, -1), new Coordinate(-1, -1)});
        Double[] data = new Double[3];
        data[Delta_Distance] = -1.;
        data[E_Length] = -1.;
        data[Full_Diffraction_Distance] = -1.;
        LinkedList<TriIdWithIntersection> interPoints = new LinkedList<TriIdWithIntersection>();
        //set default data
        DiffractionWithSoilEffetZone totData = new DiffractionWithSoilEffetZone(data, rOZone, sOZone);
        if(!hasBuildingWithHeight) {
            return totData;
        }
        LineSegment propaLine = new LineSegment(p1, p2);
        int curTri = getTriangleIdByCoordinate(p1);
        HashSet<Integer> navigationHistory = new HashSet<Integer>();

        //get source triangle id
        int curTriS = getTriangleIdByCoordinate(p2);
        if (this.triVertices.get(curTri).getBuidlingID() >= 1) {
            //receiver is in the building, so we cant compute propagation
            return totData;
        }
        if (this.triVertices.get(curTriS).getBuidlingID() >= 1) {

            //source is in the building, so we cant compute propagation
            return totData;
        }

        Coordinate[] triR = getTriangle(curTri);
        Coordinate[] triS = getTriangle(curTriS);

        double zTopoR = getTopoZByGiven3Points(triR[0], triR[1], triR[2], p1);
        double zTopoS = getTopoZByGiven3Points(triS[0], triS[1], triS[2], p2);

        if (p1.z < zTopoR || Double.isNaN(p1.z)) {
            //Z value of the receiver is low than topography, we will modify this receiver height
            p1.setCoordinate(new Coordinate(p1.x, p1.y, zTopoR + receiverDefaultHeight));
        }
        if (p2.z < zTopoS) {
            //Z value of the source is low than topography, than the propagation cant not be compute
            return totData;
        }
        while (curTri != -1) {
            navigationHistory.add(curTri);
            Coordinate[] tri = getTriangle(curTri);
            if (dotInTri(p2, tri[0], tri[1], tri[2])) {
                break;
            }
            //get the next building ID, intersection Point
            TriIdWithIntersection triIDWithIntersection = this.getNextTriWithIntersection(curTri, propaLine, navigationHistory);
            curTri = triIDWithIntersection.getTriID();
            Coordinate coorIntersection = triIDWithIntersection.getCoorIntersection();
            if (!coorIntersection.equals(new Coordinate(-1, -1, -1))) {
                interPoints.add(triIDWithIntersection);
            }
        }

        //add point receiver and point source into list head and tail.

        interPoints.addFirst(new TriIdWithIntersection(-1, p1, true));
        interPoints.addLast(new TriIdWithIntersection(-1, p2, true));
        //change Coordinate system from 3D to 2D
        LinkedList<Coordinate> newPoints = getNewCoordinateSystem(interPoints);

        double[] pointsX;
        pointsX = new double[newPoints.size()];
        double[] pointsY;
        pointsY = new double[newPoints.size()];

        for (int i = 0; i < newPoints.size(); i++) {
            pointsX[i] = newPoints.get(i).x;
            if (!Double.isNaN(newPoints.get(i).y)) {
                pointsY[i] = newPoints.get(i).y;
            } else {
                pointsY[i] = 0.;
            }
            newPoints.get(i).setCoordinate(new Coordinate(pointsX[i], pointsY[i]));
            newCoorInter.put(newPoints.get(i), interPoints.get(i));
        }
        //algo JarvisMarch to get the convex hull
        JarvisMarch jm = new JarvisMarch(new JarvisMarch.Points(pointsX, pointsY));
        JarvisMarch.Points points = jm.calculateHull();
        //if there are no useful intersection
        if (points.x.length <= 2) {
            //after jarvis march if we get the length of list of points less than 2, so we have no useful points
            return totData;
        } else {
            LinkedList<LineSegment> path = new LinkedList<LineSegment>();
            boolean isVisible = true;//check if the source and receiver is visible
            for (int i = 0; i < points.x.length - 1; i++) {
                //if the intersection point after Jarvis March is not on Building so we can sure this Source-Receiver is Invisible
                if (!newCoorInter.get(new Coordinate(points.x[i], points.y[i])).getIsIntersectionOnBuilding()) {
                    //The topography block this propagation line
                    isVisible = false;
                    break;
                } else {
                    path.add(new LineSegment(new Coordinate(points.x[i], points.y[i]), new Coordinate(points.x[i + 1], points.y[i + 1])));
                    //When we get a point we will check if this point is equal with P2 we will stop finding next point
                    if (p2.equals(newCoorInter.get(new Coordinate(points.x[i + 1], points.y[i + 1])).getCoorIntersection()) && i > 0) {
                        break;
                    }
                    //if after javis march the first point and the second point are Receiver and Source so we will quit loop and no diffraction in this case
                    else if (p2.equals(newCoorInter.get(new Coordinate(points.x[i + 1], points.y[i + 1])).getCoorIntersection()) && i == 0) {
                        // after jarvis march first point and second point are Receiver and Source
                        return totData;
                    }
                }
            }
            if (isVisible) {
                double pathDistance = 0.0;//distance of path
                //prepare data to compute pure diffraction
                //h0 in expression diffraction:the highest point intersection
                double pointHeight = 0.0;
                for(LineSegment aPath : path) {
                    pathDistance = aPath.getLength() + pathDistance;
                    if (aPath.p0.y > pointHeight) {
                        pointHeight = aPath.p0.y;
                    }
                }
                if (Double.isInfinite(pathDistance)) {
                    return totData;
                }
                //we used coordinate after change coordinate system to get the right distance.
                /*
                                double dx = path.getFirst().p0.x-path.getLast().p1.x;
                double dy = path.getFirst().p0.y-path.getLast().p1.y;
                double dz = path.getFirst().p0.z-path.getLast().p1.z;

                double distanceRandS =  Math.sqrt(dx*dx+dy*dy+dz*dz);
                 */
                double distanceRandS = path.getFirst().p0.distance(path.getLast().p1);              //distance of receiver and source
                double e = pathDistance - path.getFirst().getLength() - path.getLast().getLength();//distance without first part path and last part path
                double deltaDistance = pathDistance - distanceRandS;                                //delta distance

                data[Delta_Distance] = deltaDistance;
                data[E_Length] = e;
                data[Full_Diffraction_Distance] = pathDistance;

                //if we have soil data
                Coordinate[] firstPart = new Coordinate[2];
                Coordinate[] lastPart = new Coordinate[2];
                firstPart[0] = p1;
                //get original coordinate for first intersection with building
                firstPart[1] = newCoorInter.get(path.getFirst().p1).getCoorIntersection();

                //get original coordinate for last intersection with building
                lastPart[0] = newCoorInter.get(path.getLast().p0).getCoorIntersection();
                lastPart[1] = p2;
                //receiver-first intersection zone aims to calculate ground effect
                rOZone = factory.createLineString(firstPart);
                //last intersection-source zone aims to calculate ground effect (between rOZone and sOZone we ignore soil effet)
                sOZone = factory.createLineString(lastPart);

                totData = new DiffractionWithSoilEffetZone(data, rOZone, sOZone);
            }
            return totData;
        }
    }

    /**
     * ChangeCoordinateSystem, use original coordinate in 3D to change into a new markland in 2D with new x' computed by algo and y' is original height of point.
     * Attention this function can just be used when the points in the same plane.
     * {@link "http://en.wikipedia.org/wiki/Rotation_matrix"}
     * {@link "http://read.pudn.com/downloads93/ebook/364220/zbzh.pdf"}
     */
    private LinkedList<Coordinate> getNewCoordinateSystem(LinkedList<TriIdWithIntersection> listPoints) {
        LinkedList<Coordinate> newcoord = new LinkedList<Coordinate>();
        //get angle by ray source-receiver with the X-axis.
        double angle = new LineSegment(listPoints.getFirst().getCoorIntersection(), listPoints.getLast().getCoorIntersection()).angle();
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);

        for (int i = 0; i < listPoints.size(); i++) {
            double newX = (listPoints.get(i).getCoorIntersection().x - listPoints.get(0).getCoorIntersection().x) * cos +
                    (listPoints.get(i).getCoorIntersection().y - listPoints.get(0).getCoorIntersection().y) * sin;
            newcoord.add(new Coordinate(newX, listPoints.get(i).getCoorIntersection().z));


        }

        return newcoord;

    }


    /**
     * We will get all of building corners Z and set the building a average height using corner Z and original building height
     *
     * @param polygonWithHeight
     */
    private void setAverageBuildingHeight(List<MeshBuilder.PolygonWithHeight> polygonWithHeight) {

        for (MeshBuilder.PolygonWithHeight polygon : polygonWithHeight) {
            //When we get all of building, we will set every vertices of the same building a same Z,
            //using the Average "z+height"
            Coordinate[] buildingCoor = polygon.getGeometry().getCoordinates();
            double buildingHeight = polygon.getHeight();
            //if the building is closed
            Double sumBuildingHeight = 0.;
            Double averageBuildingHeight = 0.;
            if (buildingHeight == Double.MAX_VALUE) {
                averageBuildingHeight = buildingHeight;
            } else {
                if (buildingCoor[0].equals(buildingCoor[buildingCoor.length - 1]) && buildingCoor.length - 1 >= 3) {
                    for (int j = 0; j < buildingCoor.length - 1; j++) {
                        sumBuildingHeight += buildingCoor[j].z + buildingHeight;
                    }

                    averageBuildingHeight = sumBuildingHeight / (buildingCoor.length - 1);
                }

            }
            //set the averageBuildingZ
            polygon.setHeight(averageBuildingHeight);
        }

    }

    /**
     * Caculate the Z of intersection point
     * {@see http://en.wikipedia.org/wiki/Linear_interpolation}
     *
     * @param p1           a point of intersected segment
     * @param p2           other point of intersected segment
     * @param intersection the intersection which includes the x and y
     * @return z of intersection point
     */
    private double calculateLinearInterpolation(Coordinate p1, Coordinate p2, Coordinate intersection) {
        LinkedList<Coordinate> points = new LinkedList<Coordinate>();
        double zOfIntersection = 0.;
        points.add(p1);
        points.add(p2);
        if (intersection != null) {
            points.add(intersection);
        } else {
            return zOfIntersection;
        }
        setNaNZ0(points);
        if ((p2.y - p1.y) + p1.z != 0 && (p2.y - p1.y) != 0) {
            zOfIntersection = ((p2.z - p1.z) * (intersection.y - p1.y)) / (p2.y - p1.y) + p1.z;
        }
        else if((p2.x - p1.x) + p1.z != 0 && (p2.x - p1.x) != 0){
            zOfIntersection = ((p2.z - p1.z) * (intersection.x - p1.x)) / (p2.x - p1.x) + p1.z;
        }
        return zOfIntersection;
    }

    /**
     * Equation Plane: ax+by+cz+d=0, can be fixed by 3 given points
     * When we fix a,b,c,d by given 3 points, we can get Z of given point X,Y
     * z=-(ax+by+d)/c
     *
     * @param p1    first point
     * @param p2    second point
     * @param p3    third point
     * @param point the point which includes the x and y
     * @return z of point
     * {@see http://en.wikipedia.org/wiki/Plane_%28geometry%29}
     */

    private double getTopoZByGiven3Points(Coordinate p1, Coordinate p2, Coordinate p3, Coordinate point) {
        double a;
        double b;
        double c;
        double d;
        double topoZofPoint = 0.;
        List<Coordinate> points = new LinkedList<Coordinate>();
        points.add(p1);
        points.add(p2);
        points.add(p3);
        points.add(point);
        setNaNZ0(points);

        a = ((p2.y - p1.y) * (p3.z - p1.z) - (p2.z - p1.z) * (p3.y - p1.y));
        b = ((p2.z - p1.z) * (p3.x - p1.x) - (p2.x - p1.x) * (p3.z - p1.z));
        c = ((p2.x - p1.x) * (p3.y - p1.y) - (p2.y - p1.y) * (p3.x - p1.x));
        d = (0 - (a * p1.x + b * p1.y + c * p1.z));
        if (c != 0) {
            topoZofPoint = -(a * point.x + b * point.y + d) / c;
        }
        return topoZofPoint;

    }

    private void setNaNZ0(List<Coordinate> points) {
        for (Coordinate point : points) {
            if (Double.isNaN(point.z)) {
                point.setCoordinate(new Coordinate(point.x, point.y, 0.));
            }
        }
    }


}
