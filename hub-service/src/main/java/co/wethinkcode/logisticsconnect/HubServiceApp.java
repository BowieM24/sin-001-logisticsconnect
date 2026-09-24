package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;

public class HubServiceApp {

    public static void main(String[] args) {
        // Initialize Javalin app without starting server port to aviod race conditions 
        Javalin app = Javalin.create();

        // Register health check GET endpoint
        app.get("/health", ctx -> ctx.result("OK"));

        // Domain endpoint, Serves the place-name source of truth for Provinces
        app.get("/provinces", ctx -> {
            // Manually construct a JSON array of the recongnized provinces.
            // aviod needing external JSON mapping libraries like Jackson for a simple lists.
            String jsonResponse = "[\"Eastern Cape\", \"Free State\", \"Gauteng\", \"Limpopo\", \"Western Cape\", \"KwaZulu-Natal\", \"Mpumalanga\", \"North West\", \"Northen Cape\", \"Western Cape\"]";

            // Set content-type header so the calling client knows to parse it as JSON
            ctx.contentType("application/json");
            // Return JSON string as the HTTP response body
            ctx.result(jsonResponse);
        });
        
        // Domain endpoint, Serves the place-name source of truth for Sorting Centers
        app.get("/sorting-centers", ctx -> {
            // Manually construct JSON array of recognized sorting centers
            String jsonResponse = "[\"Johannesburg Central\", \"Cape Town Port\", \"Pretoria North\", \"Durban Harbour\", \"Bloemfontein Hub\", \"Polokwane Hub\"]";

            // Set content type header so calling client knows to parse is as JSON
            ctx.contentType("application/json");
            // Return JSON string as HTTP response body
            ctx.result(jsonResponse);
        });

        // Start accpeting HTTP requests
        app.start(7051);
    }
}
