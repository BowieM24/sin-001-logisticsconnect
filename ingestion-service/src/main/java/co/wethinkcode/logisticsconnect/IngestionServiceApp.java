package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;
import java.io.*;
import java.util.*;

public class IngestionServiceApp {

    /// Define Standard Headers
    private static final String[] HEADERS = {"hub_id", "province", "sorting_center", "active"};

    public static void main(String[] args) {
        // Initilize Javalin App without starting 
        Javalin app = Javalin.create();
        // Define a health check endpoint
        app.get("/health", ctx -> ctx.result("OK"))
        // Initialize an empty string to hold the final CSV output
        String generatedCsv = "";

        // Wrap the file reading process in a try-catch to handle missing files
        try {
            // Call the custom cleaning method, searching the root of the compiled resources directory
            List<String[]> cleanedData = cleanHubsGlobalCsv("/resources/hubs-global.csv");
            // Convert the list of string arrays back into a single, multi-line CSV string
            generatedCsv = formatAsCsv(HEADERS, cleanedData);
            System.out.println(generatedCsv);
        } catch (IOException e) {
            // If the file is missing or unreadable, print a critical error to the terminal
            System.err.println("CRITICAL: Failed to load CSV data on startup - " + e.getMessage());
        }
        
        // Assign the generated string to a final variable so the endpoint can safely serve it.
        final String csvOutput = generatedCsv;

        // We assign the generated string to a final variable so the endpoint can safely serve it.
        app.get("/hubs", ctx -> {
            // If the string is empty, the file failed to load during startup, return an HTTP 500 error indicating the server is in a bad state
            if (csvOutput.isEmpty()) {
                ctx.status(500).result("Internal Server Error: Data not loaded");
            } else {
                // Set the HTTP header so the receiving service knows this is raw CSV data  
                ctx.contentType("text/csv");
                // Send the generated CSV string as the response body
                ctx.result(csvOutput);
            }
        });
        // Start Application last
        app.start(7050);
    }

    /**
     * Reads a CSV file from the classpath, cleans the data and returns a list
     * of cleaned rows matching the target schema.
     */
    public static List<String[]> cleanHubsGlobalCsv(String fileName) throws IOException {
        /// Create a list to hold cleaned rows
        List<String[]> cleanedRows = new ArrayList<>();

        /// Using Map to track  unique sorting centers for deduplication
        /// key: Normalized Sorting Center Name, Value: The final String[] row
        Map<String, String[]> uniqueHubs = new LinkedHashMap<>();

        /// Obtain the input stream from the classpath
        InputStream inputStream = IngestionServiceApp.class.getClassLoader().getResourceAsStream(fileName);

        /// Check if the file was not found
        if (inputStream == null) {
            /// Throw an exception 
            throw new FileNotFoundException("File not found on classpath: " + fileName);
        }

        // Try-with-resources: the BufferedReader will be closed automatically 
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            boolean isFirstLine = true;

            /// Track the index of the hub_id column for duplication
            int hubIdColumnIndex = -1;
            /// Track the index of the province column for title uppercase
            int provinceColumnIndex = -1;
            ///Track the index of the sorting_center column for title uppercase and trailing whitespace
            int sortingCenterColumnIndex = -1;
            // Track the index of active column
            int activeColumnIndex = -1;

            // Read each line from the CSV file
            while ((line = reader.readLine()) != null) {
                // Remove BOM (Byte Order Mark) if present at the beginning of the file
                if (isFirstLine && line.startsWith("\uFEFF")) {
                    line = line.substring(1);
                }
                // Trim leading/trailing whitespace
                line = line.trim();

                // Skip empty lines
                if (line.isEmpty()) {
                    continue;
                }

                // Split the line by comma, -1 keeps trailing empty strings
                String[] parts = line.split(",", -1);

                // Clean each field: trim whitespace and replace empty strings with null
                for (int i = 0; i < parts.length; i++) {
                    parts[i] = parts[i].trim();
                }

                // Handle the header row to find the district column index
                if (isFirstLine) {
                    // Look through all the columns in the header
                    for (int i = 0; i < parts.length; i++) {
                        String header = parts[i].toLowerCase();
                        // Inside "hub_id"
                        if (header.equals("hub_id") || header.equals("id")) {
                            hubIdColumnIndex = i;
                            // Inside "province"
                        } else if (header.equals("province")) {
                            provinceColumnIndex = i;
                            // Inside "signal_type"
                        } else if (header.equals("sorting_center") || header.equals("sortingcenter")) {
                            sortingCenterColumnIndex = i;
                            // Inside active
                        } else if (header.equals("active")) {
                            activeColumnIndex = i;
                        }
                    }
                    isFirstLine = false;
                    continue;
                }
                // Extract and clean hub_Id 
                String id = getField(parts, hubIdColumnIndex);
                if (id != null) {
                    id = id.toUpperCase();
                }

                // Extract the Province and pass it to the helper method to format it in Title Case
                String province = capitalizeFirstLetter(getField(parts, provinceColumnIndex));

                // Extract, format and collapse spaces for Sorting Center
                String sortingCenter = getField(parts, sortingCenterColumnIndex);
                if (sortingCenter != null) {
                    /// Replace double spaces with single spaces using regular expression 
                    sortingCenter = sortingCenter.replaceAll("\\s+", " ");
                    // Format the cleaned string in Title Case
                    sortingCenter = capitalizeFirstLetter(sortingCenter);
                }

                // Extract the active status and pass it to the boolean normalization helper method
                Boolean activeBool = parseActiveFlag(getField(parts, activeColumnIndex));
                // Extract the active status and pass it to the boolean normalization helper method
                String active = activeBool != null ? activeBool.toString() : "";

                // If any core fields are missing or empty, skip the row
                if (id == null || province == null || sortingCenter == null) {
                    continue;
                }
                // Create a new, perfectly formatted string array representing this row
                String[] newRow = new String[]{id, province, sortingCenter, active};

                /// Deduplication logic: multiple hub_ids can map to the same sorting center.
                /// Use sortingCenter name (lowercased) as the unique key.
                /// if duplicate if found, latest row read from the file overwrites the previous one.
                uniqueHubs.put(sortingCenter.toLowerCase(), newRow);
            }
            // Extract all the final, deduplicated rows from the Map and add them to the final List
            cleanedRows.addAll(uniqueHubs.values());
        }
        return cleanedRows;
    }

    private static String formatAsCsv(String[] headers, List<String[]> rows) {
        StringBuilder csv = new StringBuilder();
        // Join the header array with commas and append a newline character
        csv.append(String.join(",", headers)).append("\n");
        // Loop through every data row
        for (String[] row : rows) {
            // Join the row array with commas and append a newline character
            csv.append(String.join(",", row)).append("\n");
        }
        // Convert the assembled builder back into a standard String
        return csv.toString();
    }

    // Helper Method to safely extract data from the array
    private static String getField(String[] parts, int index) {
        // If the index is invalid or the cell is empty, return null to signify missing data
        if (index < 0 || index >= parts.length || parts[index].isEmpty()) {
            return null;
        }

        // Convert the data to lowercase to check against a list of known "empty" placeholder words
        String val = parts[index].toLowerCase();
        if (val.equals("n/a") || val.equals("tbd") || val.equals("unknown") || val.equals("-") || val.equals("nan")) {
            // If the cell contains a placeholder, treat it as empty and return null
            return null;
        }
        // Return the actual data
        return parts[index];
    }

    private static String capitalizeFirstLetter(String input) {
        // If the string is null or empty, return null safely without throwing an exception
        if (input == null || input.isEmpty()) {
            return null;
        }

        // Split the string by spaces to handle multi-word names (e.g., "western cape")
        String[] words = input.split(" ");
        StringBuilder result = new StringBuilder();
        // Loop through each word
        for (String word : words) {
            if (!word.isEmpty()) {
                // Take the 1st character, make it uppercase. Take the rest of the word, make it lowercase. Append a space.
                result.append(word.substring(0, 1).toUpperCase()).append(word.substring(1).toLowerCase()).append(" ");
            }
        }
        // Convert back to string and trim the final trailing space we just appended
        return result.toString().trim();
    }

    private static Boolean parseActiveFlag(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        // Standardize the input so we only have to check lowercase versions
        String normalized = input.trim().toLowerCase();
        // Check against known "true" values
        if (normalized.equals("1") || normalized.equals("y") || normalized.equals("yes") || normalized.equals("true")) {
            return true;
        }
        // Check against known "false" values
        if (normalized.equals("0") || normalized.equals("n") || normalized.equals("no") || normalized.equals("false")) {
            return false;
        }
        // If it doesn't match any known boolean representation, return null
        return null;
    }
}
