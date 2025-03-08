/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.resources;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.graphhopper.*;
import com.graphhopper.gpx.GpxConversions;
import com.graphhopper.http.GHPointParam;
import com.graphhopper.http.GHRequestTransformer;
import com.graphhopper.http.ProfileResolver;
import com.graphhopper.jackson.MultiException;
import com.graphhopper.jackson.ResponsePathSerializer;
import com.graphhopper.util.*;
import com.graphhopper.util.exceptions.GHException;
import com.graphhopper.util.exceptions.PointNotFoundException;
import com.graphhopper.util.shapes.GHPoint;
import io.dropwizard.jersey.params.AbstractParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import javax.validation.constraints.NotNull;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.util.*;
import java.util.stream.Collectors;

import static com.graphhopper.util.Parameters.Details.PATH_DETAILS;
import static com.graphhopper.util.Parameters.Routing.*;
import static java.util.stream.Collectors.toList;
import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;

/**
 * Resource to use GraphHopper in a remote client application like mobile or browser. Note: If type
 * is json it returns the points in GeoJson array format [longitude,latitude] unlike the format "lat,lon"
 * used for the request. See the full API response format in docs/web/api-doc.md
 *
 * @author Peter Karich
 */
@Path("route")
public class RouteResource {

    private static final Logger logger = LoggerFactory.getLogger(RouteResource.class);

    private final GraphHopperConfig config;
    private final GraphHopper graphHopper;
    private final ProfileResolver profileResolver;
    private final GHRequestTransformer ghRequestTransformer;
    private final Boolean hasElevation;
    private final String osmDate;
    private final List<String> snapPreventionsDefault;

    @Inject
    public RouteResource(GraphHopperConfig config, GraphHopper graphHopper, ProfileResolver profileResolver, GHRequestTransformer ghRequestTransformer, @Named("hasElevation") Boolean hasElevation) {
        this.config = config;
        this.graphHopper = graphHopper;
        this.profileResolver = profileResolver;
        this.ghRequestTransformer = ghRequestTransformer;
        this.hasElevation = hasElevation;
        this.osmDate = graphHopper.getProperties().getAll().get("datareader.data.date");
        this.snapPreventionsDefault = Arrays.stream(config.getString("routing.snap_preventions_default", "")
                .split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @GET
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, "application/gpx+xml"})
    public Response doGet(
            @Context HttpServletRequest httpReq,
            @Context UriInfo uriInfo,
            @QueryParam(WAY_POINT_MAX_DISTANCE) @DefaultValue("0.5") double minPathPrecision,
            @QueryParam(ELEVATION_WAY_POINT_MAX_DISTANCE) Double minPathElevationPrecision,
            @QueryParam("point") @NotNull List<GHPointParam> pointParams,
            @QueryParam("type") @DefaultValue("json") String type,
            @QueryParam(INSTRUCTIONS) @DefaultValue("true") boolean instructions,
            @QueryParam(CALC_POINTS) @DefaultValue("true") boolean calcPoints,
            @QueryParam("elevation") @DefaultValue("false") boolean enableElevation,
            @QueryParam("points_encoded") @DefaultValue("true") boolean pointsEncoded,
            @QueryParam("points_encoded_multiplier") @DefaultValue("1e5") double pointsEncodedMultiplier,
            @QueryParam("profile") String profileName,
            @QueryParam(ALGORITHM) @DefaultValue("") String algoStr,
            @QueryParam("locale") @DefaultValue("en") String localeStr,
            @QueryParam(POINT_HINT) List<String> pointHints,
            @QueryParam(CURBSIDE) List<String> curbsides,
            @QueryParam(SNAP_PREVENTION) List<String> snapPreventions,
            @QueryParam(PATH_DETAILS) List<String> pathDetails,
            @QueryParam("heading") @NotNull List<Double> headings,
            @QueryParam("gpx.route") @DefaultValue("true") boolean withRoute /* default to false for the route part in next API version, see #437 */,
            @QueryParam("gpx.track") @DefaultValue("true") boolean withTrack,
            @QueryParam("gpx.waypoints") @DefaultValue("false") boolean withWayPoints,
            @QueryParam("gpx.trackname") @DefaultValue("GraphHopper Track") String trackName,
            @QueryParam("gpx.millis") String timeString) {
        StopWatch sw = new StopWatch().start();
        List<GHPoint> points = pointParams.stream().map(AbstractParam::get).collect(toList());
        boolean writeGPX = "gpx".equalsIgnoreCase(type);
        instructions = writeGPX || instructions;
        if (enableElevation && !hasElevation)
            throw new IllegalArgumentException("Elevation not supported!");

        GHRequest request = new GHRequest();
        initHints(request.getHints(), uriInfo.getQueryParameters());

        if (minPathElevationPrecision != null)
            request.getHints().putObject(ELEVATION_WAY_POINT_MAX_DISTANCE, minPathElevationPrecision);

        request.setPoints(points).
                setProfile(profileName).
                setAlgorithm(algoStr).
                setLocale(localeStr).
                setHeadings(headings).
                setPointHints(pointHints).
                setCurbsides(curbsides).
                setPathDetails(pathDetails).
                getHints().
                putObject(CALC_POINTS, calcPoints).
                putObject(INSTRUCTIONS, instructions).
                putObject(WAY_POINT_MAX_DISTANCE, minPathPrecision);

        if (uriInfo.getQueryParameters().containsKey(SNAP_PREVENTION)) {
            if (snapPreventions.size() == 1 && snapPreventions.contains(""))
                request.setSnapPreventions(List.of()); // e.g. "&snap_prevention=&" to force empty list
            else
                request.setSnapPreventions(snapPreventions);
        } else {
            // no "snap_prevention" was specified
            request.setSnapPreventions(snapPreventionsDefault);
        }

        request = ghRequestTransformer.transformRequest(request);

        PMap profileResolverHints = new PMap(request.getHints());
        profileResolverHints.putObject("profile", profileName);
        profileResolverHints.putObject("has_curbsides", !curbsides.isEmpty());
        profileName = profileResolver.resolveProfile(profileResolverHints);
        removeLegacyParameters(request.getHints());
        request.setProfile(profileName);

        GHResponse ghResponse = graphHopper.route(request);

        double took = sw.stop().getMillisDouble();
        String logStr = (httpReq.getRemoteAddr() + " " + httpReq.getLocale() + " " + httpReq.getHeader("User-Agent")) + " " + points + ", took: " + String.format("%.1f", took) + "ms, algo: " + algoStr + ", profile: " + profileName;

        if (ghResponse.hasErrors()) {
            logger.info(logStr + " " + ghResponse);
            return Response.status(Response.Status.BAD_REQUEST).
                    entity(new MultiException(ghResponse.getErrors())).
                    type(writeGPX ? "application/gpx+xml" : MediaType.APPLICATION_JSON).
                    build();
        } else {
            logger.info(logStr + ", alternatives: " + ghResponse.getAll().size()
                    + ", distance0: " + ghResponse.getBest().getDistance()
                    + ", weight0: " + ghResponse.getBest().getRouteWeight()
                    + ", time0: " + Math.round(ghResponse.getBest().getTime() / 60000f) + "min"
                    + ", points0: " + ghResponse.getBest().getPoints().size()
                    + ", debugInfo: " + ghResponse.getDebugInfo());
            return writeGPX ?
                    gpxSuccessResponseBuilder(ghResponse, timeString, trackName, enableElevation, withRoute, withTrack, withWayPoints, Constants.VERSION).
                            header("X-GH-Took", "" + Math.round(took)).
                            build()
                    :
                    Response.ok(ResponsePathSerializer.jsonObject(ghResponse, new ResponsePathSerializer.Info(config.getCopyrights(), Math.round(took), osmDate), instructions, calcPoints, enableElevation, pointsEncoded, pointsEncodedMultiplier)).
                            header("X-GH-Took", "" + Math.round(took)).
                            type(MediaType.APPLICATION_JSON).
                            build();
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response doPost(@NotNull GHRequest request, @Context HttpServletRequest httpReq) {
        if (!request.hasSnapPreventions())
            request.setSnapPreventions(snapPreventionsDefault);

        StopWatch sw = new StopWatch().start();
        request = ghRequestTransformer.transformRequest(request);

        if (Helper.isEmpty(request.getProfile()) && request.getCustomModel() != null)
            // throw a dedicated exception here, otherwise a missing profile is still caught in Router
            throw new IllegalArgumentException("The 'profile' parameter is required when you use the `custom_model` parameter");

        PMap profileResolverHints = new PMap(request.getHints());
        profileResolverHints.putObject("profile", request.getProfile());
        profileResolverHints.putObject("has_curbsides", !request.getCurbsides().isEmpty());
        request.setProfile(profileResolver.resolveProfile(profileResolverHints));
        removeLegacyParameters(request.getHints());

        GHResponse ghResponse = graphHopper.route(request);
        boolean instructions = request.getHints().getBool(INSTRUCTIONS, true);
        boolean enableElevation = request.getHints().getBool("elevation", false);
        boolean calcPoints = request.getHints().getBool(CALC_POINTS, true);
        boolean pointsEncoded = request.getHints().getBool("points_encoded", true);
        double pointsEncodedMultiplier = request.getHints().getDouble("points_encoded_multiplier", 1e5);

        double took = sw.stop().getMillisDouble();
        String infoStr = httpReq.getRemoteAddr() + " " + httpReq.getLocale() + " " + httpReq.getHeader("User-Agent");
        String logStr = infoStr + " " + request.getPoints().size() + ", took: "
                + String.format("%.1f", took) + " ms, algo: " + request.getAlgorithm() + ", profile: " + request.getProfile()
                + ", custom_model: " + request.getCustomModel();

        if (ghResponse.hasErrors()) {
            throw new MultiException(ghResponse.getErrors());
        } else {
            logger.info(logStr + ", alternatives: " + ghResponse.getAll().size()
                    + ", distance0: " + ghResponse.getBest().getDistance()
                    + ", weight0: " + ghResponse.getBest().getRouteWeight()
                    + ", time0: " + Math.round(ghResponse.getBest().getTime() / 60000f) + "min"
                    + ", points0: " + ghResponse.getBest().getPoints().size()
                    + ", debugInfo: " + ghResponse.getDebugInfo());
            return Response.ok(ResponsePathSerializer.jsonObject(ghResponse, new ResponsePathSerializer.Info(config.getCopyrights(), Math.round(took), osmDate), instructions, calcPoints, enableElevation, pointsEncoded, pointsEncodedMultiplier)).
                    header("X-GH-Took", "" + Math.round(took)).
                    type(MediaType.APPLICATION_JSON).
                    build();
        }
    }

    @POST
    @Path("bulk")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, "application/gpx+xml"})
    public Response doPost(
            @Context HttpServletRequest httpReq,
            @Context UriInfo uriInfo,
            @QueryParam(WAY_POINT_MAX_DISTANCE) @DefaultValue("0.5") double minPathPrecision,
            BulkRouteRequest bulkRouteRequest,
            @QueryParam("type") @DefaultValue("json") String type,
            @QueryParam(INSTRUCTIONS) @DefaultValue("true") boolean instructions,
            @QueryParam(CALC_POINTS) @DefaultValue("true") boolean calcPoints,
            @QueryParam("profile") @DefaultValue("car") String profile,
//            @QueryParam("weighting") @DefaultValue("fastest") String weighting,
            @QueryParam(ALGORITHM) @DefaultValue("") String algoStr,
            @QueryParam("locale") @DefaultValue("en") String localeStr,
            @QueryParam(Parameters.Details.PATH_DETAILS) List<String> pathDetails) {
        boolean writeGPX = "gpx".equalsIgnoreCase(type);
        instructions = writeGPX || instructions;

        StopWatch sw = new StopWatch().start();
        List<GHPoint> points = new ArrayList<>();
        points.add(bulkRouteRequest.getOriginPoint());
        for(Destination destination: bulkRouteRequest.getDestinations()){
            points.add(destination.getDestinationPoint());
        }
        if(points.isEmpty()) {
            throw new WebApplicationException(errorResponse(new IllegalArgumentException("You have to pass at least one point"), writeGPX));
        }
//        if (!encodingManager.supports(vehicleStr)) {
//            throw new WebApplicationException(errorResponse(new IllegalArgumentException("Vehicle not supported: " + vehicleStr), writeGPX));
//        }
        if(bulkRouteRequest.getDestinations().isEmpty()){
            throw new WebApplicationException(errorResponse(new IllegalArgumentException("You have to pass at least destination"), writeGPX));
        }
        ObjectNode json = JsonNodeFactory.instance.objectNode();
        final ObjectNode info = json.putObject("info");
        info.putArray("copyrights")
                .add("Swift Routes")
                .add("powered by GraphHopper");
        ArrayNode jsonPathList = json.putArray("data");
        List<GHPoint> requestPoints = new ArrayList<>();
        for(Destination destination: bulkRouteRequest.getDestinations()){
            requestPoints.clear();
            requestPoints.add(destination.getDestinationPoint());
            requestPoints.add(bulkRouteRequest.getOriginPoint());
            GHRequest request;
            request = new GHRequest(requestPoints);
            initHints(request.getHints(), uriInfo.getQueryParameters());
            request.setProfile(profile).
//                    setVehicle(encodingManager.getEncoder(vehicleStr).toString()).
//                    setWeighting(weighting).
        setAlgorithm(algoStr).
                    setLocale(localeStr).
                    setPathDetails(pathDetails).
                    getHints().
                    putObject(CALC_POINTS, calcPoints).
                    putObject(INSTRUCTIONS, instructions).
                    putObject(WAY_POINT_MAX_DISTANCE, minPathPrecision);
            GHResponse ghResponse = null;
            try {
                ghResponse = graphHopper.route(request);
            } catch (PointNotFoundException e){
                ObjectNode jsonPath = jsonPathList.addObject();
                jsonPath.put("id", destination.getId());
                for(Throwable error: ghResponse.getErrors()){
                    jsonPath.put("error", error.getMessage());
                }
            }

            if (ghResponse.hasErrors()) {
                //throw new WebApplicationException(errorResponse(ghResponse.getErrors(), writeGPX));
                ObjectNode jsonPath = jsonPathList.addObject();
                jsonPath.put("id", destination.getId());
                for(Throwable error: ghResponse.getErrors()){
                    jsonPath.put("error", error.getMessage());
                }
            } else {
                for (ResponsePath ar : ghResponse.getAll()) {
                    ObjectNode jsonPath = jsonPathList.addObject();
                    jsonPath.put("id", destination.getId());
                    jsonPath.put("distance", Helper.round(ar.getDistance(), 3));
                    jsonPath.put("time", ar.getTime());
                }
            }
        }
        return Response.ok(json).build();
    }

    public static void removeLegacyParameters(PMap hints) {
        // these parameters should only be used to resolve the profile, but should not be passed to GraphHopper
        hints.remove("weighting");
        hints.remove("vehicle");
        hints.remove("edge_based");
        hints.remove("turn_costs");
    }

    private static Response.ResponseBuilder gpxSuccessResponseBuilder(GHResponse ghRsp, String timeString, String
            trackName, boolean enableElevation, boolean withRoute, boolean withTrack, boolean withWayPoints, String version) {
        if (ghRsp.getAll().size() > 1) {
            throw new IllegalArgumentException("Alternatives are currently not yet supported for GPX");
        }

        long time = timeString != null ? Long.parseLong(timeString) : System.currentTimeMillis();
        InstructionList instructions = ghRsp.getBest().getInstructions();
        return Response.ok(GpxConversions.createGPX(instructions, trackName, time, enableElevation, withRoute, withTrack, withWayPoints, version, instructions.getTr()), "application/gpx+xml").
                header("Content-Disposition", "attachment;filename=" + "GraphHopper.gpx");
    }

    static void initHints(PMap m, MultivaluedMap<String, String> parameterMap) {
        for (Map.Entry<String, List<String>> e : parameterMap.entrySet()) {
            if (e.getValue().size() == 1) {
                m.putObject(Helper.camelCaseToUnderScore(e.getKey()), Helper.toObject(e.getValue().get(0)));
            } else {
                // TODO e.g. 'point' parameter occurs multiple times and we cannot throw an exception here
                //  unknown parameters (hints) should be allowed to be multiparameters, too, or we shouldn't use them for
                //  known parameters either, _or_ known parameters must be filtered before they come to this code point,
                //  _or_ we stop passing unknown parameters altogether.
                // throw new WebApplicationException(String.format("This query parameter (hint) is not allowed to occur multiple times: %s", e.getKey()));
                // see also #1976
            }
        }
    }

    private Response errorResponse(List<Throwable> t, boolean writeGPX) {
        if (writeGPX) {
            return xmlErrorResponse(t);
        } else {
            return jsonErrorResponse(t);
        }
    }

    private Response errorResponse(Throwable t, boolean writeGPX) {
        return errorResponse(Collections.singletonList(t), writeGPX);
    }

    private Response jsonErrorResponse(List<Throwable> errors) {
        ObjectNode json = JsonNodeFactory.instance.objectNode();
        json.put("message", getMessage(errors.get(0)));
        ArrayNode errorHintList = json.putArray("hints");
        for (Throwable t : errors) {
            ObjectNode error = errorHintList.addObject();
            error.put("message", getMessage(t));
            error.put("details", t.getClass().getName());
            if (t instanceof GHException) {
                ((GHException) t).getDetails().forEach(error::putPOJO);
            }
        }
        return Response.status(SC_BAD_REQUEST).entity(json).build();
    }

    private Response xmlErrorResponse(Collection<Throwable> list) {
        if (list.isEmpty())
            throw new RuntimeException("errorsToXML should not be called with an empty list");

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.newDocument();
            Element gpxElement = doc.createElement("gpx");
            gpxElement.setAttribute("creator", "GraphHopper");
            gpxElement.setAttribute("version", "1.1");
            doc.appendChild(gpxElement);

            Element mdElement = doc.createElement("metadata");
            gpxElement.appendChild(mdElement);

            Element extensionsElement = doc.createElement("extensions");
            mdElement.appendChild(extensionsElement);

            Element messageElement = doc.createElement("message");
            extensionsElement.appendChild(messageElement);
            messageElement.setTextContent(list.iterator().next().getMessage());

            Element hintsElement = doc.createElement("hints");
            extensionsElement.appendChild(hintsElement);

            for (Throwable t : list) {
                Element error = doc.createElement("error");
                hintsElement.appendChild(error);
                error.setAttribute("message", t.getMessage());
                error.setAttribute("details", t.getClass().getName());
            }
            return Response.status(SC_BAD_REQUEST).entity(doc).build();
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        }
    }

    private String getMessage(Throwable t) {
        if (t.getMessage() == null)
            return t.getClass().getSimpleName();
        else
            return t.getMessage();
    }
}
