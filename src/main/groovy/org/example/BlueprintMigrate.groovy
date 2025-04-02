import de.itdesign.clarity.rest.ClarityRestClient
import de.itdesign.clarity.rest.RestResponse
import groovy.json.JsonSlurper
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

def getAvailableVisualIdFromDB(String visualId) {
    String lastThreeDigits = visualId.takeRight(3)
    String availableVisualId = null
    Sql sql
    try {
        sql = getDBConnection()
        availableVisualId = sql.firstRow("SELECT available_visual_id FROM BLP_BLUEPRINT_VISUALS WHERE SUBSTR(available_visual_id, -3) = ?", [lastThreeDigits])?.available_visual_id
        println("query : ${availableVisualId}")
    } catch (Exception e) {
        LOG.error("Error while fetching visualId from DB: ${e.message}", e)
    }
    return availableVisualId
}

def copyBlueprintByPost(String internalId, ClarityRestClient rest) {
    RestResponse resp
    String urlString = "private/copy/blueprints/${internalId}"
    Map<String, Object> body = [name: "Standard Projectt"]
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

def getExistingModuleLabels(String blueprintInternalId, ClarityRestClient rest) {
    RestResponse resp
    String urlString = "private/blueprints/${blueprintInternalId}/visuals"
    List existingLabels = []

    try {
        resp = rest.GET(urlString)
        def responseData = resp?.jsonMap()
        println("Response Data: ${responseData}")

        def visualIds = responseData?._results?.collect { it._internalId }

        visualIds.each { id ->
            String detailUrl = "private/blueprints/${blueprintInternalId}/visuals/${id}"
            try {
                RestResponse detailResp = rest.GET(detailUrl)
                def detailData = detailResp?.jsonMap()
                if (detailData?.label) {
                    existingLabels << detailData.label
                }
            } catch (Exception e) {
                println("Error fetching label for visual ID ${id}: ${e.message}")
            }
        }
        println("Existing Labels: ${existingLabels}")
    } catch (Exception e) {
        println("Error while fetching existing module labels: ${e.message}")
        e.printStackTrace()
    }
    return existingLabels
}

def postModulesToBlueprint(String createdblueprintInternalId,String blueprintInternalId, List<Map<String, Object>> modules, ClarityRestClient rest) {
    String visualId
    RestResponse resp
    String urlString = "private/blueprints/${blueprintInternalId}/visuals"

    List existingLabels = getExistingModuleLabels(createdblueprintInternalId, rest)
    println("Existing Module Labels in blueprint: ${existingLabels}")

    modules.each { module ->
        println("loop: ${visualId}")
        if (existingLabels.contains(module.label)) {

            return
        }
        println("module visual : ${module.visualId.toString()}")
        String availableVisualId = getAvailableVisualIdFromDB(module.visualId.toString())
        if (availableVisualId) {
             visualId = availableVisualId
            println("avialble visula: ${visualId}")
        }


        Map<String, Object> moduleBody = [
                type            : module.type,
                sequence        : module.sequence,
                attributeName   : module.attributeName,
                label           : module.label,
                visualId        : visualId,
                includedInFlyout: module.includedInFlyout
        ]
        println("Posting Body: ${moduleBody}")

        try {
            resp = rest.POST(urlString, moduleBody)
            println("Post Response: ${resp?.jsonMap()}")
        } catch (Exception e) {
            println("Error while posting module: ${e.message}")
            e.printStackTrace()
        }
    }
}

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

def main() {
    Sql sql
    ClarityRestClient rest

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

    try {
        sql = getDBConnection()
        rest = new ClarityRestClient("admin", sql.getConnection())


        println("query: ")
        String encodedData = request.getParameter("data")
        def jsonSlurper = new JsonSlurper()
        def parsedData = jsonSlurper.parseText(encodedData)

        String internalId = parsedData.standardBlueprintId
        println("internalId : ${internalId}")

        Map<String, Object> copiedBlueprint = copyBlueprintByPost(internalId, rest)
        println("copiedBlueprint: ${copiedBlueprint}")
        if (copiedBlueprint.isEmpty()) {
            println("Failed to create blueprint.")
        } else {
            String createdBlueprintInternalId = copiedBlueprint._internalId.toString()
            println("Created Blueprint ID: ${createdBlueprintInternalId}")

            Map<String, Object> createdBlueprintData = getBlueprintById(createdBlueprintInternalId, rest)
            if (createdBlueprintData.isEmpty()) {
                LOG.error("Failed to retrieve created blueprint by internalId: ${createdBlueprintInternalId}")
            } else {
                String blueprintCode = createdBlueprintData.code
                println("Blueprint Code: ${blueprintCode}")

                Map<String, Object> editedBlueprint = editBlueprint(blueprintCode, rest)
                String copiedBlueprintInternalId = editedBlueprint._internalId.toString()
                println("Copied Blueprint ID: ${copiedBlueprintInternalId}")

                postModulesToBlueprint(createdBlueprintInternalId,copiedBlueprintInternalId, parsedData.modules, rest)
                println("posted modules")
                def sectionsIds = getSectionsFromBlueprint(copiedBlueprintInternalId, rest)
                println("section ids: ${sectionsIds}")

                def sortedSections = sectionsIds.sort { a, b -> a.name <=> b.name }

                def summarySectionId = sortedSections.find { it.name == 'summary' }?.internalId
                def templateSectionId = sortedSections.find { it.name == 'createFromTemplate' }?.internalId
                def settingsSectionId = sortedSections.find { it.name == 'settings' }?.internalId

                summarySectionId = summarySectionId ?: sortedSections.min { it.internalId }?.internalId
                templateSectionId = templateSectionId ?: sortedSections.sort { it.internalId }[-2]?.internalId
                settingsSectionId = settingsSectionId ?: sortedSections.sort { it.internalId }[-1]?.internalId

                parsedData.details.sections.each { section ->
                    println("section :${section}")
                    String sectionId = ""
                    if (section.name == "summary") {
                        sectionId = summarySectionId.toString()
                    } else if (section.name == "settings") {
                        sectionId = settingsSectionId.toString()
                    } else if (section.name == "createFromTemplate") {
                        sectionId = templateSectionId.toString()
                    }
                    section.fields.each { field ->
                        println("field : ${field}")
                        postFieldToSection(copiedBlueprintInternalId, sectionId, field, rest)

                    }
                    println("posted fields")
                }

                parsedData.rules.each { rule ->
                    postRule(copiedBlueprintInternalId, rule, rest)
                }
                println("rules posted")

                Map<String, Object> putData = [
                        mode: "PUBLISHED"
                ]

                updateBlueprint(copiedBlueprintInternalId, putData, rest)
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
