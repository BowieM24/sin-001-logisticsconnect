package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;
import org.apache.activemq.ActiveMQConnectionFactory;
import javax.jms.*;

import co.wethinkcode.logisticsconnect.mq.MqConfig; 

public class DelayStageServiceApp {
    //Main method serves as the entry point when the Java application runs
    public static void main(String[] args) {
        // Initialize Javalin app without starting it to avoid race condition
        Javalin app = Javalin.create();
        // Register a health check GET endpoint
        app.get("/health", ctx -> ctx.result("OK"));

        // Add domain endpoints for delay-stage-service here.
        // Register a POST endpoint to receive transit delay stage changes (0-8, e.g. weather shutdowns)
        app.post("/stage/state-change", ctx -> {
            // Extract the 'level' query parameter from incoming HTTP request URL
            String levelParam = ctx.queryParam("level");

            // Validate level param
            if (levelParam == null || levelParam.isEmpty()) {
                // Return HTTP 400 Bad Request status
                ctx.status(400).result("Missing 'level' query parameter");
                // Stop further execution
                return;
            }

            // Wrap parsing and network logic in try-catch to handle bad data gracefully
            try {
                // Convert string parameter into int
                int level = Integer.parseInt(levelParam);

                // Validate that provided level falls within allowed boundry (0 - 8)
                if (level < 0 || level > 8) {
                    // Return HTTP 400 Bad Requesy status
                    ctx.status(400).result("Delay stage level must be between 0 and 8");
                    // Stop further execution
                    return; 
                }

                // Call helper method to broadcast the validated delay stage to ActiveMQ
                publishDelayStage(level);
                // Return HTTP 200 OK status confirming the sate was successful
                ctx.result("Delay stage updated to " + level + " and published to MQ");    
            // Catch exception thrown if a non-numeric string is passed
            } catch (NumberFormatException e) {
                // Return HTTP 400 Bad Request
                ctx.status(400).result("Level must be a valid integer");
            // Catch network exceptions if ActiveMQ broker is offline
            } catch (JMSException e) {
                // Return HTTP 500 Internal Server Error 
                ctx.status(500).result("Failed to publish to ActiveMQ: " + e.getMessage());
            }
        });

        // Start Javalin app binded to port 7050
        app.start(7052);
    }

    /**
     * Connects ActiveMQ broker and publishes new transit delay stage
     * to designated topic so downstream services can adjust transit times
     */
    // Helper method
    private static void publishDelayStage(int level) throws JMSException {
        // Create a connection pointing to ActiveMQ broker defined in MqConfig
        ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(MqConfig.BROKER_URL);

        // Try-with to ensure Connection ans Session sre cleanly closed
        try (Connection connection = connectionFactory.createConnection();
                // Create non-transactional session that automatically acknowlwdges sent messages
                Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {

                    // Start network connection to ActiveMQ broker
                    connection.start();

                    // Create destination object represnting sepcific Topic
                    Destination destination = session.createTopic(MqConfig.TOPIC);

                    // Try-with to safely open and close message producer
                    try (MessageProducer producer = session.createProducer(destination)) {
                        // Set delivery mode to non-persistent so the broker doesn't waste disk space saving outdated live states
                        producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);

                        // Format a raw JSON string containing the new delay stage level
                        String jsonPayload = String.format("{\"delayStage\": %d}", level);
                        // Wrap the JSON string inside a standard JMS TextMessage envelope
                        TextMessage message = session.createTextMessage(jsonPayload);

                        // Transmit the compiled message payload over the network to the ActiveMQ topic
                        producer.send(message);
                        // Print a confirmation to the local terminal console for debugging purposes
                        System.out.println("Published delay stage change: " + jsonPayload);
                    }
                }
            }
        }
    

