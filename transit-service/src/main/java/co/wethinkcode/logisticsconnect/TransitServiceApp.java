package co.wethinkcode.logisticsconnect;

import java.util.concurrent.atomic.AtomicInteger;

import javax.jms.Connection;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.apache.activemq.ActiveMQConnectionFactory;

import io.javalin.Javalin;

import co.wethinkcode.logisticsconnect.mq.MqConfig;

public class TransitServiceApp {

    // Thread-safe variable to hold latest delay stage received from ActiveMQ.
    // Intiliaze at 0 (means normal transit times, no delays).
    private static final AtomicInteger currentDelayStage = new AtomicInteger(0);

    // Main method serves as entry point when Javalin app runs
    public static void main(String[] args) {

        // Intiliaize Javalin app without starting it to avoid race condition
        Javalin app = Javalin.create();

        // Register a health check GET endpoint
        app.get("/health", ctx -> ctx.result("OK"));

        // Register domain endpoint to estimate package arrival windows
        app.get("transit/estimate", ctx -> {
            // Extract 'hub' query parameter from request URL
            String hub = ctx.queryParam("hub");

            // Validate parameter is not empty, check if user forgot to provide hub parameter
            if (hub == null || hub.isEmpty()) {
                // Return HTTP 400 Bad Request status if validation fails
                ctx.status(400).result("Missing 'hub' query parameter");
                // Stop further execution
                return;
            }

            // Safely read the live delay stage from the thread-safe AtomicInteger
            int delayStage = currentDelayStage.get();

            // Transit calculation logic: Base transit time is 24 hours.
            // Every level of delay adds an additional 12 hours to the window
            int estimateArrivalWindowHours = 24 + (delayStage * 12);

            // Construct JSON-Formatted string dynamicall with the calculated estimates
            String jsonResponse = String.format(
                    "{\"hub\": \"%s\", \"delayStage\": %d, \"estimateArrivalWindowHours\": %d}",
                    hub, delayStage, estimateArrivalWindowHours
            );

            // Set content-type header so calling client knows to parse it as JSON
            ctx.contentType("application/json");
            // Return compiled JSON string as HTTP response body
            ctx.result(jsonResponse);

        });

        // Try-catch to connect to external message broker
        try {
            // Call helper method that sets up the ActiveMQ subscriber
            startDelayStageSubscriber();
            // Catch JMS exception if broker is offline
        } catch (JMSException e) {
            System.err.println("critical: Failed to connect to ActiveMQ broker: " + e.getMessage());
        }

        // Start accepting HTTP request
        app.start(7053);
    }

    /**
     * Connects to the ActiveMQ broker as a Consumer. It listens to the
     * package-status-topic and asynchronously updates the internal delay stage
     * variable whenever the Delay Stage Service broadcasts a shutdown or delay.
     */
    private static void startDelayStageSubscriber() throws JMSException {
        // Setup the connection factory pointing to the ActiveMQ broker address
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(MqConfig.BROKER_URL);
        // Create new network connection to the broker
        Connection connection = factory.createConnection();
        // Start connection to allow message to flow inward
        connection.start();

        // Create non-transactional session that automatically acknowledges received messages
        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

        // Map Destination object to specific Topic string defined in MqConfig
        Destination topic = session.createTopic(MqConfig.TOPIC);

        // Create MessageConsumer that specifically listens to that topic
        MessageConsumer consumer = session.createConsumer(topic);

        // Attach an asynchronous listener that that runs on a background thread
        consumer.setMessageListener(message -> {
            // Ensure incoming message is text-based before attempting to parse it
            if (message instanceof TextMessage) {
                try {
                    // Extract raw text payload from JMS message enelope
                    String payload = ((TextMessage) message).getText();
                    // Log incomig live update to console
                    System.out.println("TransitService received live update: " + payload);

                    // Verify payload contains expected JSON key
                    if (payload.contains("\"delayStage\"")) {
                        // Split string at colon to separate key from the value
                        String[] parts = payload.split(":");
                        // Ensure there is a right hand side to parse
                        if (parts.length > 1) {
                            // Strip out brackets, quotes and whitespace using regex, leaving only digits
                            String numberString = parts[1].replaceAll("[^0-9]", "");
                            // Convert cleaned string into an integer
                            int level = Integer.parseInt(numberString);

                            // Safely overwrite old delay stage with the new one
                            currentDelayStage.set(level);
                        }
                    }
                    // Catch parsing or JMS errors without crashing background listener thread
                } catch (JMSException | NumberFormatException e) {
                    System.err.println("Failed to parse incoming MQ message: " + e.getMessage());
                }
            }

        });

    }
}
