import groovy.json.JsonSlurper
import groovy.json.JsonBuilder
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

class BlueprintMigrate {
    static final Logger LOG = LogManager.getLogger(BlueprintMigrate.class)

    // Function to get blueprint by ID
    static Map<String, Object> getBlueprintById(String internalId, String username, String password) {
        def urlString = "http://10.0.0.98:7080/ppm/rest/v1/private/blueprints/${internalId}"
        URL url = new URL(urlString)

        HttpURLConnection connection = (HttpURLConnection) url.openConnection()
        connection.setRequestMethod("GET")
        connection.setRequestProperty("Content-Type", "application/json")

        String credentials = "${username}:${password}"
        String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes("UTF-8"))
        connection.setRequestProperty("Authorization", "Basic ${encodedCredentials}")

        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
            InputStreamReader reader = new InputStreamReader(connection.inputStream)
            def jsonResponse = new JsonSlurper().parse(reader)
            connection.disconnect()
            LOG.info("Blueprint data retrieved successfully: ${jsonResponse}")
            return jsonResponse
        } else {
            LOG.error("Failed to retrieve blueprint. Response code: ${connection.responseCode}")
            connection.disconnect()
            return [:]
        }
    }

    // Function to post a new blueprint
    static Map<String, Object> postBlueprint(Map<String, Object> blueprintData, String username, String password) {
        def urlString = "http://10.0.0.98:7080/ppm/rest/v1/private/blueprints"
        URL url = new URL(urlString)

        HttpURLConnection connection = (HttpURLConnection) url.openConnection()
        connection.setRequestMethod("POST")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setDoOutput(true)

        String credentials = "${username}:${password}"
        String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes("UTF-8"))
        connection.setRequestProperty("Authorization", "Basic ${encodedCredentials}")

        // Log the data to be sent
        LOG.info("Data to be sent: ${new JsonBuilder(blueprintData).toString()}")

        String jsonRequestBody = new JsonBuilder(blueprintData).toString()

        OutputStream os = connection.getOutputStream()
        os.write(jsonRequestBody.getBytes("UTF-8"))
        os.flush()
        os.close()

        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
            InputStreamReader reader = new InputStreamReader(connection.inputStream)
            def jsonResponse = new JsonSlurper().parse(reader)
            connection.disconnect()
            LOG.info("Blueprint created successfully: ${jsonResponse}")
            return jsonResponse
        } else {
            LOG.error("Failed to create blueprint. Response code: ${connection.responseCode}")
            connection.disconnect()
            return [:]
        }
    }

    // In the copyBlueprint method
    static Map<String, Object> copyBlueprint(String code, String username, String password) {
        def urlString = "http://10.0.0.98:7080/ppm/rest/v1/private/copyBlueprint"
        URL url = new URL(urlString)

        HttpURLConnection connection = (HttpURLConnection) url.openConnection()
        connection.setRequestMethod("POST")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setDoOutput(true)

        String credentials = "${username}:${password}"
        String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes("UTF-8"))
        connection.setRequestProperty("Authorization", "Basic ${encodedCredentials}")

        // Build the JSON body for copying the blueprint with only 'source' and 'mode'
        Map<String, Object> body = [
                source: code,  // Using the 'code' from the retrieved blueprint
                mode: "edit"   // You can modify the mode if needed (e.g., "edit", "copy", etc.)
        ]
        // Log the JSON request body before sending
        def jsonRequestBody = new JsonBuilder(body).toString()
        LOG.info("Sending copyBlueprint request with body: ${jsonRequestBody}")
        println(jsonRequestBody)

        OutputStream os = connection.getOutputStream()
        os.write(jsonRequestBody.getBytes("UTF-8"))
        os.flush()
        os.close()

        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
            InputStreamReader reader = new InputStreamReader(connection.inputStream)
            def jsonResponse = new JsonSlurper().parse(reader)
            connection.disconnect()
            LOG.info("Blueprint copied successfully: ${jsonResponse}")
            return jsonResponse
        } else {
            LOG.error("Failed to copy blueprint. Response code: ${connection.responseCode}")
            connection.disconnect()
            return [:]
        }
    }


    // Function to post the modules to the new blueprint
    static void postModulesToBlueprint(Map<String, Object> createdBlueprint, List<Map<String, Object>> modules, String username, String password) {
        String newInternalId = createdBlueprint._internalId.toString()  // Extracting internalId from the created blueprint
        println(newInternalId)
        String urlString = "http://10.0.0.98:7080/ppm/rest/v1/private/blueprints/${newInternalId}/visuals"
        println(newInternalId)// URL for the POST request

        modules.each { module ->
            // Build the request body for each module with only the required fields
            Map<String, Object> moduleBody = [
                    type            : module.type,           // type
                    sequence        : module.sequence,       // sequence
                    attributeName   : module.attributeName,  // attributeName
                    label           : module.label,          // label
                    visualId        : module.visualId,       // visualId
                    includedInFlyout: module.includedInFlyout // includedInFlyout
            ]

            // Log the module data to be sent
            LOG.info("Preparing to post module: ${moduleBody}")
            println(moduleBody)

            // Prepare the URL for the POST request
            URL url = new URL(urlString)

            // Open the connection and set the request properties
            HttpURLConnection connection = (HttpURLConnection) url.openConnection()
            connection.setRequestMethod("POST")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setDoOutput(true)

            // Add authorization header
            String credentials = "${username}:${password}"
            String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes("UTF-8"))
            connection.setRequestProperty("Authorization", "Basic ${encodedCredentials}")

            // Convert the moduleBody to JSON and send it as the POST request body
            String jsonRequestBody = new JsonBuilder(moduleBody).toString()
            println(jsonRequestBody)

            // Send the request
            OutputStream os = connection.getOutputStream()
            os.write(jsonRequestBody.getBytes("UTF-8"))
            os.flush()
            os.close()

            // Check the response from the server
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                InputStreamReader reader = new InputStreamReader(connection.inputStream)
                def jsonResponse = new JsonSlurper().parse(reader)
                connection.disconnect()
                LOG.info("Module posted successfully: ${jsonResponse}")
            } else {
                LOG.error("Failed to post module. Response code: ${connection.responseCode}")
                connection.disconnect()
            }
        }
    }

    // Main function to execute the steps
    static void main(String[] args) {
        String username = "admin"
        String password = "TotalDE12!"
        String internalId = "5011005"

        // Sample modules data
        List<Map<String, Object>> modules = [
                [_internalId: 5018120, sequence: 0, enablePropNav: false, enableModalCreate: false, relativePath: "project.conversation", enableQuickCreate: true, attributeName: "issues", label: "Issues", type: "link", category: "MODULE", visualId: 5011068, includedInFlyout: false],
                [label: "Tasks", type: "link", visualId: 5011065, _internalId: 5018128, sequence: 1, enablePropNav: false, enableModalCreate: false, relativePath: "project.tasks", enableQuickCreate: true, attributeName: "tasks", category: "MODULE", assocBlueprintId: 5011065, includedInFlyout: false]
        ]

        // Get the blueprint data
        Map<String, Object> blueprintData = getBlueprintById(internalId, username, password)
        if (blueprintData.isEmpty()) {
            LOG.error("Blueprint not found for internalId: ${internalId}")
        } else {
            println("Retrieved Blueprint Data: ${blueprintData}")

            // Modify the name of the blueprint
            blueprintData.name = "Demo Final Copy"

            // Replace localhost URL with the actual IP address
            blueprintData._links.mode = blueprintData._links.mode.replace("http://localhost:8080", "http://10.0.0.98:7080")
            blueprintData._links.context = blueprintData._links.context.replace("http://localhost:8080", "http://10.0.0.98:7080")
            blueprintData._links.type = blueprintData._links.type.replace("http://localhost:8080", "http://10.0.0.98:7080")
            blueprintData._links.associatedObject = blueprintData._links.associatedObject.replace("http://localhost:8080", "http://10.0.0.98:7080")

            // Transform other fields to match the required format
            blueprintData.publishedBy = "Administrator, CA PPM"
            blueprintData.lastUsedDate = null
            blueprintData.lastUpdatedBy = "Administrator, CA PPM"
            blueprintData.lastPublishedDate = "2025-02-27T13:39:08"
            blueprintData.lastModifiedDate = "2025-02-27T13:39:08"
            blueprintData.standardBlueprintId = 5011005
            blueprintData.investmentCountUsingBlueprint = 1
            blueprintData.associatedObject = null
            blueprintData.publishTarget = null
            blueprintData.isSystem = false
            blueprintData._isFavorite = false
            blueprintData.isDefault = false

            // Remove unwanted fields
            blueprintData.remove("_internalId")
            blueprintData.remove("_self")
            blueprintData.remove("sections")  // Remove sections field
            blueprintData.remove("visuals")   // Remove visuals field
            blueprintData.remove("code")      // Remove code field
            blueprintData.classifier = null

            // Log the transformed data before posting
            def jsonForPost = new JsonBuilder(blueprintData).toString()
            LOG.info("Prepared data for POST request: ${jsonForPost}")

            // Print the JSON to console
            println("Prepared data for POST request:")
            println(jsonForPost)

            // Send the transformed data in the POST request
            Map<String, Object> createdBlueprint = postBlueprint(blueprintData, username, password)
            println("Created Blueprint Response: ${createdBlueprint}")

            if (createdBlueprint.isEmpty()) {
                LOG.error("Failed to create blueprint.")
            } else {
                // Capture the internal ID directly after creation
                String createdBlueprintInternalId = createdBlueprint._internalId.toString()

                println("Created Blueprint ID: ${createdBlueprintInternalId}")
                // Ensure you're using the correct ID here

                // Get the code of the newly created blueprint using its internalId
                // Now, use the internalId of the created blueprint to get the 'code'
                Map<String, Object> createdBlueprintData = getBlueprintById(createdBlueprintInternalId, username, password)
                if (createdBlueprintData.isEmpty()) {
                    LOG.error("Failed to retrieve created blueprint by internalId: ${createdBlueprintInternalId}")
                } else {
                    // Retrieve the 'code' from the blueprint data
                    String blueprintCode = createdBlueprintData.code
                    println("Blueprint Code: ${blueprintCode}")


                    // Post the modules to the new blueprint using the created ID
                    postModulesToBlueprint(createdBlueprint, modules, username, password)
                    }
                }
            }
        }
    }
