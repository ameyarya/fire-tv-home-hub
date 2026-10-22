package com.homecontrol.tv;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cities to pick a location from, grouped by time zone, so sunrise and sunset need no typing. A city is
 * close enough: across a city, sunset differs by a minute or two (a whole time zone can be off by more
 * than half an hour, which is why the zone alone isn't used).
 */
final class Places {

    static final class City {
        final String name;
        final double lat, lon;

        City(String name, double lat, double lon) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }
    }

    static final class Zone {
        final String id;
        final String label;
        final List<City> cities = new ArrayList<>();

        Zone(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    /** In the order they're offered, roughly west to east. */
    static final Map<String, Zone> ZONES = new LinkedHashMap<>();

    private static Zone zone(String id, String label, Object... cities) {
        Zone z = new Zone(id, label);
        for (int i = 0; i < cities.length; i += 3) z.cities.add(new City((String) cities[i], (Double) cities[i + 1], (Double) cities[i + 2]));
        ZONES.put(id, z);
        return z;
    }

    static {
        zone("Pacific/Honolulu", "Hawaii", "Honolulu", 21.307, -157.858);
        zone("America/Anchorage", "Alaska", "Anchorage", 61.218, -149.900, "Fairbanks", 64.838, -147.716, "Juneau", 58.302, -134.420);
        zone("America/Los_Angeles", "Pacific Time (US & Canada)",
                "Los Angeles", 34.052, -118.244, "San Francisco", 37.775, -122.419, "San Jose", 37.338, -121.886,
                "San Diego", 32.716, -117.161, "Sacramento", 38.582, -121.494, "Fresno", 36.738, -119.787,
                "Seattle", 47.606, -122.332, "Portland", 45.515, -122.679, "Las Vegas", 36.170, -115.140,
                "Vancouver", 49.283, -123.121);
        zone("America/Phoenix", "Arizona", "Phoenix", 33.448, -112.074, "Tucson", 32.222, -110.975);
        zone("America/Denver", "Mountain Time (US & Canada)",
                "Denver", 39.739, -104.990, "Salt Lake City", 40.761, -111.891, "Albuquerque", 35.084, -106.650,
                "Boise", 43.615, -116.202, "Calgary", 51.045, -114.057, "Edmonton", 53.546, -113.494);
        zone("America/Chicago", "Central Time (US & Canada)",
                "Chicago", 41.878, -87.630, "Houston", 29.760, -95.370, "Dallas", 32.777, -96.797, "Austin", 30.267, -97.743,
                "San Antonio", 29.424, -98.494, "Minneapolis", 44.978, -93.265, "Kansas City", 39.100, -94.579,
                "St. Louis", 38.627, -90.199, "Nashville", 36.163, -86.781, "New Orleans", 29.951, -90.072,
                "Milwaukee", 43.039, -87.906, "Oklahoma City", 35.468, -97.516, "Winnipeg", 49.895, -97.138,
                "Mexico City", 19.433, -99.133);
        zone("America/New_York", "Eastern Time (US & Canada)",
                "New York", 40.713, -74.006, "Boston", 42.360, -71.059, "Philadelphia", 39.953, -75.165,
                "Washington, DC", 38.907, -77.037, "Baltimore", 39.290, -76.612, "Atlanta", 33.749, -84.388,
                "Miami", 25.762, -80.192, "Orlando", 28.538, -81.379, "Tampa", 27.951, -82.457, "Charlotte", 35.227, -80.843,
                "Raleigh", 35.780, -78.639, "Pittsburgh", 40.441, -79.996, "Detroit", 42.331, -83.046,
                "Cleveland", 41.499, -81.694, "Columbus", 39.961, -82.999, "Indianapolis", 39.768, -86.158,
                "Toronto", 43.653, -79.383, "Montreal", 45.502, -73.567, "Ottawa", 45.421, -75.697);
        zone("America/Halifax", "Atlantic Time (Canada)", "Halifax", 44.649, -63.575);
        zone("America/Sao_Paulo", "Brasília", "São Paulo", -23.551, -46.633, "Rio de Janeiro", -22.907, -43.173);
        zone("America/Argentina/Buenos_Aires", "Buenos Aires", "Buenos Aires", -34.604, -58.382);
        zone("Europe/London", "London, Dublin, Lisbon",
                "London", 51.507, -0.128, "Manchester", 53.481, -2.243, "Edinburgh", 55.953, -3.188,
                "Dublin", 53.350, -6.260, "Lisbon", 38.722, -9.139);
        zone("Europe/Paris", "Central Europe",
                "Paris", 48.857, 2.352, "Berlin", 52.520, 13.405, "Madrid", 40.417, -3.704, "Rome", 41.903, 12.496,
                "Amsterdam", 52.368, 4.904, "Brussels", 50.850, 4.352, "Vienna", 48.208, 16.374, "Zurich", 47.377, 8.542,
                "Stockholm", 59.329, 18.069, "Oslo", 59.914, 10.752, "Copenhagen", 55.676, 12.568, "Warsaw", 52.230, 21.012,
                "Prague", 50.076, 14.438);
        zone("Europe/Athens", "Eastern Europe", "Athens", 37.984, 23.728, "Helsinki", 60.170, 24.938, "Bucharest", 44.427, 26.103,
                "Kyiv", 50.450, 30.523);
        zone("Africa/Cairo", "Cairo", "Cairo", 30.044, 31.236);
        zone("Africa/Johannesburg", "South Africa", "Johannesburg", -26.204, 28.047, "Cape Town", -33.925, 18.424);
        zone("Africa/Lagos", "West Africa", "Lagos", 6.524, 3.379);
        zone("Africa/Nairobi", "East Africa", "Nairobi", -1.292, 36.822);
        zone("Europe/Istanbul", "Istanbul", "Istanbul", 41.008, 28.978);
        zone("Europe/Moscow", "Moscow", "Moscow", 55.756, 37.617);
        zone("Asia/Dubai", "Gulf", "Dubai", 25.205, 55.271, "Abu Dhabi", 24.454, 54.377);
        zone("Asia/Karachi", "Pakistan", "Karachi", 24.861, 67.010, "Lahore", 31.520, 74.359);
        zone("Asia/Kolkata", "India",
                "New Delhi", 28.614, 77.209, "Mumbai", 19.076, 72.878, "Bengaluru", 12.972, 77.595, "Chennai", 13.083, 80.271,
                "Kolkata", 22.573, 88.364, "Hyderabad", 17.385, 78.487, "Pune", 18.520, 73.857, "Ahmedabad", 23.023, 72.571,
                "Jaipur", 26.912, 75.787, "Lucknow", 26.847, 80.947);
        zone("Asia/Dhaka", "Bangladesh", "Dhaka", 23.811, 90.413);
        zone("Asia/Bangkok", "Indochina", "Bangkok", 13.756, 100.502, "Ho Chi Minh City", 10.823, 106.630, "Jakarta", -6.208, 106.846);
        zone("Asia/Singapore", "Singapore, Malaysia", "Singapore", 1.352, 103.820, "Kuala Lumpur", 3.139, 101.687);
        zone("Asia/Shanghai", "China", "Shanghai", 31.230, 121.474, "Beijing", 39.904, 116.407, "Shenzhen", 22.543, 114.058);
        zone("Asia/Hong_Kong", "Hong Kong", "Hong Kong", 22.320, 114.170);
        zone("Asia/Manila", "Philippines", "Manila", 14.600, 120.984);
        zone("Asia/Seoul", "Korea", "Seoul", 37.567, 126.978);
        zone("Asia/Tokyo", "Japan", "Tokyo", 35.676, 139.650, "Osaka", 34.694, 135.502);
        zone("Australia/Perth", "Western Australia", "Perth", -31.951, 115.861);
        zone("Australia/Adelaide", "Adelaide", "Adelaide", -34.929, 138.601);
        zone("Australia/Brisbane", "Brisbane", "Brisbane", -27.470, 153.026);
        zone("Australia/Sydney", "Sydney, Melbourne", "Sydney", -33.869, 151.209, "Melbourne", -37.814, 144.963,
                "Canberra", -35.281, 149.130);
        zone("Pacific/Auckland", "New Zealand", "Auckland", -36.849, 174.763, "Wellington", -41.287, 174.776);
    }

    private Places() {
    }

    /**
     * The zone whose cities to offer first: the TV's own if listed, else the listed zone with the same
     * UTC offset today (America/Vancouver → Pacific Time), else null.
     */
    static Zone forTv(java.time.ZoneId tv) {
        Zone z = ZONES.get(tv.getId());
        if (z != null) return z;
        java.time.Instant now = java.time.Instant.now();
        java.time.ZoneOffset off = tv.getRules().getOffset(now);
        for (Zone x : ZONES.values()) if (java.time.ZoneId.of(x.id).getRules().getOffset(now).equals(off)) return x;
        return null;
    }

    /** The listed city nearest to a saved location, to show it by name in Settings ("" when far from any). */
    static String nearest(double lat, double lon) {
        String best = "";
        double bestD = 0.5;   // degrees: about 50 km
        for (Zone z : ZONES.values()) {
            for (City c : z.cities) {
                double d = Math.hypot(c.lat - lat, (c.lon - lon) * Math.cos(Math.toRadians(lat)));
                if (d < bestD) {
                    bestD = d;
                    best = c.name;
                }
            }
        }
        return best;
    }
}
