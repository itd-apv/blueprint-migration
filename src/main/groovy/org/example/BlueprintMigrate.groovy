import de.itdesign.clarity.rest.ClarityRestClient
import de.itdesign.clarity.rest.RestResponse
import org.apache.logging.log4j.LogManager
import com.niku.union.config.ConfigurationManager
import com.niku.union.config.properties.Database
import groovy.sql.Sql
import java.sql.DriverManager

LOG = LogManager.getLogger("BlueprintMigrate")

Sql getDBConnection() {
    Sql sql
    try {
        Database database = ConfigurationManager.instance.properties.database.first()
        connection = DriverManager.getConnection(database.url, database.username, database.password)
        sql = new Sql(connection)
    } catch (Exception e) {
        println(e.printStackTrace())

    }
    sql
}

// Function to get the available_visual_id from the database based on the last 3 digits of visualId
def getAvailableVisualIdFromDB(String visualId) {
    // Extract last 3 digits of the visualId
    String lastThreeDigits = visualId.takeRight(3)
    String availableVisualId = null
    Sql sql
    try {
        sql = getDBConnection()
        availableVisualId = sql.rows("SELECT available_visual_id FROM BLP_BLUEPRINT_VISUALS WHERE SUBSTR(available_visual_id, -3) = ?", [lastThreeDigits])?.available_visual_id
    } catch (Exception e) {
        LOG.error("Error while fetching visualId from DB: ${e.message}", e)
    }
    return availableVisualId
}

// Function to copy a blueprint
def copyBlueprintByPost(String internalId, ClarityRestClient rest) {
    RestResponse resp
    String urlString = "private/copy/blueprints/${internalId}"
    Map<String, Object> body = [name: "New Copy Demo"]
    println("url: ${urlString}")
    try {
        resp = rest.POST(urlString, body)
    } catch (Exception e) {
        println("Error while copying blueprint: ${e.message}")
        e.printStackTrace()
        return [:]
    }
    resp?.jsonMap()
}


// Function to patch a blueprint
def updateBlueprint(String internalId, Map<String, Object> patchData, ClarityRestClient rest) {
    RestResponse resp
    String urlString = "private/blueprints/${internalId}"
    try {
        resp = rest.PUT(urlString, patchData, ["x-api-force-patch": "true"])
    } catch (Exception e) {
        println("Error while updating blueprint: ${e.message}")
        e.printStackTrace()
        return [:]
    }
    resp?.jsonMap()
}

// Function to get blueprint by ID
def getBlueprintById(String internalId, ClarityRestClient rest) {
    RestResponse resp
    String urlString = "private/blueprints/${internalId}"
    try {
        resp = rest.GET(urlString)
    } catch (Exception e) {
        println("Error while retrieving blueprint: ${e.message}")
        e.printStackTrace()
        return [:]
    }
    resp?.jsonMap()
}

// Function to edit a blueprint
def editBlueprint(String code, ClarityRestClient rest) {
    RestResponse resp
    String urlString = "private/copyBlueprint"
    Map<String, Object> body = [source: code, action: "edit"]
    try {
        resp = rest.POST(urlString, body)
    } catch (Exception e) {
        println("Error while editing blueprint: ${e.message}")
        e.printStackTrace()
        return [:]
    }
    resp?.jsonMap()
}

// Function to post modules to the new blueprint
def postModulesToBlueprint(String blueprintInternalId, List<Map<String, Object>> modules, ClarityRestClient rest) {
    RestResponse resp
    String urlString = "private/blueprints/${blueprintInternalId}/visuals"
    modules.each { module ->
        String availableVisualId = getAvailableVisualIdFromDB(module.visualId.toString())
        if (availableVisualId) {
            module.visualId = availableVisualId
        }
        Map<String, Object> moduleBody = [
                type            : module.type,
                sequence        : module.sequence,
                attributeName   : module.attributeName,
                label           : module.label,
                visualId        : module.visualId,
                includedInFlyout: module.includedInFlyout
        ]
        try {
            resp = rest.POST(urlString, moduleBody)
        } catch (Exception e) {
            println("Error while posting module: ${e.message}")
            e.printStackTrace()
            return [:]
        }
        resp?.jsonMap()
    }
}

// Function to get sections from a blueprint
def getSectionsFromBlueprint(String blueprintInternalId, ClarityRestClient rest) {

    RestResponse resp
    String urlString = "private/blueprints/${blueprintInternalId}/sections"
    try {
        resp = rest.GET(urlString)
    } catch (Exception e) {
        println("Error while posting module: ${e.message}")
        e.printStackTrace()
        return [:]
    }
    def response = resp?.jsonMap()._results.collect { [internalId: it._internalId] }
    LOG.info("section response: ${response}")
    println("section response: ${response}")
    return response
}

// Function to post a field to a specific section
def postFieldToSection(String blueprintId, String sectionId, Map<String, Object> field, ClarityRestClient rest) {
    RestResponse resp
    String urlString = "private/blueprints/${blueprintId}/sections/${sectionId}/fields"
    try {
        resp = rest.POST(urlString, field)
    } catch (Exception e) {
        println("Error while posting field: ${e.message}")
        e.printStackTrace()
        return [:]
    }
    resp?.jsonMap()
}


// Function to post a rule to the specified endpoint
def postRule(String copiedBlueprintInternalId, Map<String, Object> rule, ClarityRestClient rest) {
    RestResponse resp
    String urlString = "private/rules"
    rule.put("associatedInstanceId", copiedBlueprintInternalId)
    try {
        resp = rest.POST(urlString, rule)
    } catch (Exception e) {
        println("Error while posting rule: ${e.message}")
        e.printStackTrace()
        return [:]
    }
    resp?.jsonMap()
}

// Main function to execute the steps
def main() {
    Sql sql
    ClarityRestClient rest
    String internalId = "5011005"
    println(internalId)

    // Sample modules data
    List<Map<String, Object>> modules = [
            [_internalId: 5018120, sequence: 0, enablePropNav: false, enableModalCreate: false, relativePath: "project.conversation", enableQuickCreate: true, attributeName: "hierarchy", label: "Hierarchy", type: "link", category: "MODULE", visualId: 5011074, includedInFlyout: false],
            [label: "To Dos", type: "link", visualId: 5011072, _internalId: 5018128, sequence: 1, enablePropNav: false, enableModalCreate: false, relativePath: "project.tasks", enableQuickCreate: true, attributeName: "obaTodos", category: "MODULE", assocBlueprintId: 5011065, includedInFlyout: false]
    ]

    // Define your fields with the categorized sections: "summary", "settings", "createFromTemplate"
    List<Map<String, Object>> sections = [
            [
                    "name"  : "summary",
                    "fields": [
                            [
                                    "name"       : "actuals",
                                    "metadataURL": "actuals",
                                    "column"     : 1,
                                    "row"        : 1,
                                    "width"      : 1,
                                    "height"     : 1
                            ],
                            [
                                    "name"       : "budgetBenefitTotal",
                                    "metadataURL": "budgetBenefitTotal",
                                    "column"     : 2,
                                    "row"        : 1,
                                    "width"      : 1,
                                    "height"     : 1
                            ]
                    ]
            ],
            [
                    "name"  : "createFromTemplate",
                    "fields": [
                            [
                                    "name"       : "budgetBenefitTotal",
                                    "metadataURL": "budgetBenefitTotal",
                                    "column"     : 2,
                                    "row"        : 1,
                                    "width"      : 1,
                                    "height"     : 1
                            ]
                    ]
            ],
            [
                    "name"  : "settings",
                    "fields": [
                            [
                                    "name"       : "chargeCode",
                                    "metadataURL": "chargeCode",
                                    "column"     : 1,
                                    "row"        : 0,
                                    "width"      : 1,
                                    "height"     : 1
                            ]
                    ]
            ]
    ]

    // Define a list of rules to post
    List<Map<String, Object>> rules = [
            [
                    "name"                : "Test Rule 1",
                    "category"            : "ui",
                    "associatedObjectCode": "odf_blueprint",
                    "associatedInstanceId": 5018035,
                    "objectCode"          : "z_demo"
            ],
            [
                    "name"                : "Test Rule 2",
                    "category"            : "ui",
                    "associatedObjectCode": "odf_blueprint",
                    "associatedInstanceId": 5018035,
                    "objectCode"          : "z_demo"
            ]
    ]
    println("before try")
    try {
        sql = getDBConnection()
        rest = new ClarityRestClient("admin", sql.getConnection())

        Map<String, Object> copiedBlueprint = copyBlueprintByPost(internalId, rest)
        println("copiedBlueprint: ${copiedBlueprint}")
        LOG.info("copiedBlueprint: ${copiedBlueprint}")
        if (copiedBlueprint.isEmpty()) {
            println("Failed to create blueprint.")
            LOG.info("Failed to create blueprint.")
        } else {
            String createdBlueprintInternalId = copiedBlueprint._internalId.toString()
            LOG.info("Created Blueprint ID: ${createdBlueprintInternalId}")
            println("Created Blueprint ID: ${createdBlueprintInternalId}")

            // Now, use the internalId of the created blueprint to get the 'code'
            Map<String, Object> createdBlueprintData = getBlueprintById(createdBlueprintInternalId, rest)
            if (createdBlueprintData.isEmpty()) {
                LOG.error("Failed to retrieve created blueprint by internalId: ${createdBlueprintInternalId}")
            } else {
                // Retrieve the 'code' from the blueprint data
                String blueprintCode = createdBlueprintData.code
                LOG.info("Blueprint Code: ${blueprintCode}")
                println("Blueprint Code: ${blueprintCode}")

                Map<String, Object> editedBlueprint = editBlueprint(blueprintCode, rest)
                String copiedBlueprintInternalId = editedBlueprint._internalId.toString()
                LOG.info("Copied Blueprint ID: ${copiedBlueprintInternalId}")
                println("Copied Blueprint ID: ${copiedBlueprintInternalId}")

                // Post the modules to the new blueprint using the created ID
                postModulesToBlueprint(copiedBlueprintInternalId, modules, rest)
                // Step 1: Get sections from the copied blueprint
                def sectionsIds = getSectionsFromBlueprint(copiedBlueprintInternalId, rest)
                LOG.info("section ids: ${sectionsIds}")
                println("section ids: ${sectionsIds}")

                // Sort the sections based on name to assign lowest, second lowest, and highest section IDs
                def sortedSections = sectionsIds.sort { a, b -> a.name <=> b.name }

                // Assign section IDs for each category
                def summarySectionId = sortedSections.find { it.name == 'summary' }?.internalId
                def templateSectionId = sortedSections.find { it.name == 'createFromTemplate' }?.internalId
                def settingsSectionId = sortedSections.find { it.name == 'settings' }?.internalId

                // If section IDs are not found, fallback to appropriate order (lowest, second highest, highest)
                summarySectionId = summarySectionId ?: sortedSections.min { it.internalId }?.internalId
                templateSectionId = templateSectionId ?: sortedSections.sort { it.internalId }[-2]?.internalId
                // Second highest section
                settingsSectionId = settingsSectionId ?: sortedSections.sort { it.internalId }[-1]?.internalId
                // Highest section

                LOG.info("Summary Section ID: ${summarySectionId}")
                LOG.info("Settings Section ID: ${settingsSectionId}")
                LOG.info("Template Section ID: ${templateSectionId}")

                // Post the fields for each section in order, one by one
                sections.each { section ->
                    String sectionId = ""
                    if (section.name == "summary") {
                        sectionId = summarySectionId.toString()
                    } else if (section.name == "settings") {
                        sectionId = settingsSectionId.toString()
                    } else if (section.name == "createFromTemplate") {
                        sectionId = templateSectionId.toString()
                    }

                    // Post each field in the section
                    section.fields.each { field ->
                        postFieldToSection(copiedBlueprintInternalId, sectionId, field, rest)
                    }
                    println("posted fields")
                }

                // Post the rules for the copied blueprint
                rules.each { rule ->
                    postRule(copiedBlueprintInternalId, rule, rest)
                }
                LOG.info("rules posted")
                println("rules posted")

                //Create the patch data
                Map<String, Object> putData = [
                        mode: "PUBLISHED"  // Updating the mode to "PUBLISHED"
                ]

                // Perform the PATCH request
                updateBlueprint(copiedBlueprintInternalId, putData, rest)
                LOG.info("updated blueprint")
                println("updated blueprint")
            }
        }
    }
    catch (Exception e) {
        println(e.printStackTrace())
    } finally {
        rest?.close()
        sql?.close()
    }
}

main()
