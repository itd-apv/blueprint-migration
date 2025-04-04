import de.itdesign.clarity.rest.ClarityRestClient
import de.itdesign.clarity.rest.RestResponse
import groovy.json.JsonSlurper
import org.apache.logging.log4j.LogManager
import com.niku.union.config.ConfigurationManager
import com.niku.union.config.properties.Database
import groovy.sql.Sql
import java.sql.DriverManager
import groovy.json.JsonOutput


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

def createBlueprintByPost(String internalId, String name, ClarityRestClient rest) {
    RestResponse resp
    String urlString = "private/copy/blueprints/${internalId}"
    Map<String, Object> body = [name: name]
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
    println("get exist module: ${urlString}")
    List existingLabels = []

    try {
        resp = rest.GET(urlString)
        def responseData = resp?.jsonMap()
        println("Response Data for modules: ${responseData}")

        def visualIds = responseData?._results?.collect { it._internalId }
        println("all intenallll ids :${visualIds}")

        visualIds.each { id ->
            String detailUrl = "private/blueprints/${blueprintInternalId}/visuals/${id}"
            try {
                RestResponse detailResp = rest.GET(detailUrl)
                def detailData = detailResp?.jsonMap()
                if (detailData?.label) {
                    existingLabels << detailData.label
                    println("list: ${existingLabels}")
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

def postModulesToBlueprint( String blueprintInternalId, List<Map<String, Object>> modules, ClarityRestClient rest) {
    String visualId
    RestResponse resp
    String urlString = "private/blueprints/${blueprintInternalId}/visuals"

    modules.each { module ->
        println("Processing module: ${module.label}")

        println("Module visual ID: ${module.visualId.toString()}")
        String availableVisualId = getAvailableVisualIdFromDB(module.visualId.toString())
        if (availableVisualId) {
            visualId = availableVisualId
            println("Available visual ID: ${visualId}")
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
def postVisualsToBlueprint(String blueprintInternalId, List<Map<String, Object>> visuals, ClarityRestClient rest) {
    RestResponse resp
    String urlString = "private/blueprints/${blueprintInternalId}/visuals"

    visuals.each { visual ->
        println("Processing visual: ${visual.label}")

        Map<String, Object> visualBody = [
                type           : visual.type,
                sequence       : visual.sequence,
                attributeName  : visual.attributeName,
                blueprintType  : visual.blueprintType,
                label          : visual.label,
                visualId       : visual.visualId
        ]

        println("Posting Visual Body: ${visualBody}")

        try {
            resp = rest.POST(urlString, visualBody)
            println("Post Response for Visual: ${resp?.jsonMap()}")
        } catch (Exception e) {
            println("Error while posting visual: ${e.message}")
            e.printStackTrace()
        }
    }
}

def deleteModulesNotInParsedList(String copiedBlueprintInternalId, List<Map<String, Object>> modules, ClarityRestClient rest) {
    RestResponse resp
    List existingLabelsInNew = getExistingModuleLabels(copiedBlueprintInternalId, rest)
    println("Existing Module Labels in New Blueprint: ${existingLabelsInNew}")

    List parsedModuleLabels = modules.collect { it.label }
    println("Parsed Module Labels: ${parsedModuleLabels}")

    existingLabelsInNew.each { label ->
        if (!parsedModuleLabels.contains(label)) {
            def moduleId = getModuleIdByLabel(copiedBlueprintInternalId, label, rest)
            println("Module ID to delete: ${moduleId}")
            if (moduleId) {
                String deleteUrl = "private/blueprints/${copiedBlueprintInternalId}/visuals/${moduleId}"
                println("Delete URL: ${deleteUrl}")
                try {
                    resp = rest.DELETE(deleteUrl)
                    println("Deleted module '${label}' with ID ${moduleId}. Response: ${resp?.jsonMap()}")
                } catch (Exception e) {
                    println("Error while deleting module '${label}': ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }
}

def getModuleIdByLabel(String blueprintInternalId, String label, ClarityRestClient rest) {
    String baseUrl = "private/blueprints/${blueprintInternalId}/visuals"
    RestResponse resp

    try {
        resp = rest.GET(baseUrl)
        def visuals = resp?.jsonMap()?._results ?: []
        println("All Visuals: ${visuals}")

        for (visual in visuals) {
            String visualId = visual._internalId
            String visualUrl = "${baseUrl}/${visualId}"

            RestResponse visualResp = rest.GET(visualUrl)
            def visualDetails = visualResp?.jsonMap()

            println("Visual Details for ID ${visualId}: ${visualDetails}")

            if (visualDetails?.label == label) {
                println("Found Module: ${visualDetails}")
                return visualDetails._internalId
            }
        }
        println("Module with label '${label}' not found in blueprint ${blueprintInternalId}")
        return null
    } catch (Exception e) {
        println("Error while fetching module ID for label '${label}': ${e.message}")
        e.printStackTrace()
        return null
    }
}

def getSectionsFromBlueprint(String blueprintInternalId, ClarityRestClient rest) {

    RestResponse resp
    String urlString = "private/blueprints/${blueprintInternalId}/sections"
    println("get section url: ${urlString}")
    try {
        resp = rest.GET(urlString)
    } catch (Exception e) {
        println("Error while posting module: ${e.message}")
        e.printStackTrace()
        return [:]
    }
    def response = resp?.jsonMap()._results.collect { [internalId: it._internalId] }
    println("section response: ${response}")
    return response
}

//def postFieldToSection(String blueprintId, String sectionId, Map<String, Object> field, ClarityRestClient rest) {
//    RestResponse resp
//    String urlString = "private/blueprints/${blueprintId}/sections/${sectionId}/fields"
//    println("url: ${urlString}")
//
//    try {
//        Map<String, Object> formattedField = [
//                name: field.name,
//                metadataURL: field.name,
//                column: field.layout?.col ?: 0,
//                row: field.layout?.row ?: 0,
//                width: field.layout?.sizeX ?: 1,
//                height: field.layout?.sizeY ?: 1
//        ]
//
//        String jsonBody = JsonOutput.toJson(formattedField)
//        println("Request Body: ${jsonBody}")
//
//        resp = rest.POST(urlString, formattedField)
//        println("Response: ${resp.jsonMap()}")
//    } catch (Exception e) {
//        println("Error while posting field: ${e.message}")
//        e.printStackTrace()
//        return [:]
//    }
//    resp?.jsonMap()
//}

import groovy.json.JsonOutput

import groovy.json.JsonOutput

def postFieldToSection(String blueprintId, String sectionId, Map<String, Object> field, ClarityRestClient rest) {
    RestResponse resp
    String urlString = "private/blueprints/${blueprintId}/sections/${sectionId}/fields"
    println("URL: ${urlString}")
    def postedFieldNames = []

    try {
        // Step 1: Fetch Existing Fields
        def existingFieldsResp = rest.GET(urlString)
        def existingFields = existingFieldsResp?.jsonMap()?._results ?: []
        def existingFieldIds = existingFields.collect { it._internalId }
        println("Existing Field IDs: ${existingFieldIds}")


        // Step 4: Post New Field
        Map<String, Object> formattedField = [
                name       : field.name,
                metadataURL: field.name,
                column     : field.layout?.col ?: 0,
                row        : field.layout?.row ?: 0,
                width      : field.layout?.sizeX ?: 1,
                height     : field.layout?.sizeY ?: 1
        ]
        postedFieldNames << field.name
        println("posted fiekds: ${postedFieldNames}")

        String jsonBody = JsonOutput.toJson(formattedField)
        println("Request Body: ${jsonBody}")

        resp = rest.POST(urlString, formattedField)
        println("Response: ${resp.jsonMap()}")

    // Step 2: Get Names for Each Field ID
        def existingFieldNames = []
        existingFieldIds.each { id ->
            String fieldDetailUrl = "${urlString}/${id}"
            try {
                RestResponse detailResp = rest.GET(fieldDetailUrl)
                def detailData = detailResp?.jsonMap()
                println("section json: ${detailData}")
                if (detailData?.name) {
                    existingFieldNames << detailData.name
                    println("Fetched Field Name: ${detailData.name}")
                }
            } catch (Exception e) {
                println("Error fetching field details for ID ${id}: ${e.message}")
            }
        }
        println("Existing Field Names: ${existingFieldNames}")

        // Step 5: Compare and Delete Extra Fields
        deleteExtraFields(blueprintId, sectionId, existingFieldNames, postedFieldNames, rest)

    } catch (Exception e) {
        println("Error while posting field: ${e.message}")
        e.printStackTrace()
        return [:]
    }
    return resp?.jsonMap()
}
def deleteExtraFields(String blueprintId, String sectionId, List<String> existingFieldNames, List<String> parsedFieldNames, ClarityRestClient rest) {
    println("parsed fields: ${parsedFieldNames}")
    def extraFields = existingFieldNames - parsedFieldNames
    println("extraaa :${extraFields}")

    extraFields.each { extraFieldName ->
        def fieldId = getFieldIdByName(blueprintId, sectionId, extraFieldName, rest)
        if (fieldId) {
            def deleteUrl = "private/blueprints/${blueprintId}/sections/${sectionId}/fields/${fieldId}"
            try {
                RestResponse deleteResp = rest.DELETE(deleteUrl)
                println("Deleted extra field '${extraFieldName}' with ID ${fieldId}. Response: ${deleteResp?.jsonMap()}")
            } catch (Exception e) {
                println("Error deleting extra field '${extraFieldName}': ${e.message}")
            }
        } else {
            println("Field '${extraFieldName}' not found. Skipping deletion.")
        }
    }
}

def getFieldIdByName(String blueprintId, String sectionId, String fieldName, ClarityRestClient rest) {
    String urlString = "private/blueprints/${blueprintId}/sections/${sectionId}/fields"
    try {
        RestResponse resp = rest.GET(urlString)
        def fields = resp?.jsonMap()?._results ?: []

        def field = fields.find { it.name == fieldName }
        return field?._internalId
    } catch (Exception e) {
        println("Error fetching field ID for '${fieldName}': ${e.message}")
        return null
    }
}




def postRule(String copiedBlueprintInternalId, Map<String, Object> rule, ClarityRestClient rest) {
    RestResponse resp
    String urlString = "private/rules"

    Map<String, Object> postData = [
            name: rule.name ?: "Default Name",
            isActive: rule.isActive ?: false,
            category: rule.category ?: "ui",
            associatedObjectCode: rule.associatedObjectCode ?: "odf_blueprint",
            description: rule.description ?: "No description",
            associatedInstanceId: copiedBlueprintInternalId,
            objectCode: rule.id ?: "project"
    ]

    try {
        resp = rest.POST(urlString, postData)
        println("Response: ${resp.jsonMap()}")
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

    try {
        sql = getDBConnection()
        rest = new ClarityRestClient("admin", sql.getConnection())


        println("query: ")
        String encodedData = request.getParameter("data")
        def jsonSlurper = new JsonSlurper()
        def parsedData = jsonSlurper.parseText(encodedData)

        String existingInternalId = parsedData.blueprintId

        String internalId = parsedData.standardBlueprintId
        println("internalId : ${internalId}")

        String name = parsedData.details.name
        println("name: ${name}")

        Map<String, Object> createdBlueprint = createBlueprintByPost(internalId, name,rest)
        println("createdBlueprint: ${createdBlueprint}")
        if (createdBlueprint.isEmpty()) {
            println("Failed to create blueprint.")
        } else {
            String createdBlueprintInternalId = createdBlueprint._internalId.toString()
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

                postModulesToBlueprint(copiedBlueprintInternalId,parsedData.modules, rest)
                println("posted modules")

                postVisualsToBlueprint(copiedBlueprintInternalId,parsedData.visuals, rest)


                deleteModulesNotInParsedList(copiedBlueprintInternalId, parsedData.visuals, rest)
                def sectionsIds = getSectionsFromBlueprint(copiedBlueprintInternalId, rest)
                println("section ids: ${sectionsIds}")

                def sortedSections = sectionsIds.sort { a, b -> a.internalId <=> b.internalId }

                def summarySectionId
                def templateSectionId
                def stakeHolderSectionId
                def settingsSectionId

                if (sortedSections.size() == 4) {
                    summarySectionId = sortedSections[0]?.internalId
                    templateSectionId = sortedSections[1]?.internalId
                    stakeHolderSectionId = sortedSections[2]?.internalId
                    settingsSectionId = sortedSections[3]?.internalId
                    println("Assigned IDs for 4 sections:")
                } else if (sortedSections.size() == 3) {
                    summarySectionId = sortedSections[0]?.internalId
                    templateSectionId = sortedSections[1]?.internalId
                    settingsSectionId = sortedSections[2]?.internalId
                    println("Assigned IDs for 3 sections:")
                } else {
                    println("Unexpected number of sections: ${sortedSections.size()}")
                }

                parsedData.details.sections.each { section ->
                    println("Processing section: ${section.label}")
                    String sectionId = ""

                    def label = section.label?.trim()?.toLowerCase()  // Normalize label

                    if (label == "summary" || label == "project summary") {
                        sectionId = summarySectionId?.toString()
                        println("Assigned summary ID: ${sectionId}")
                    }
                    if (label == "stakeholders") {
                        sectionId = stakeHolderSectionId?.toString()
                        println("Assigned stakeholders ID: ${sectionId}")
                    }
                    if (label == "settings") {
                        sectionId = settingsSectionId?.toString()
                        println("Assigned settings ID: ${sectionId}")
                    }

                    // Fallback condition
                    if (!sectionId) {
                        sectionId = settingsSectionId?.toString() ?: templateSectionId?.toString()
                        println("Fallback section ID: ${sectionId}")
                    }

                    if (sectionId) {
                        section.fields.each { field ->
                            println("Posting field: ${field}")
                            postFieldToSection(copiedBlueprintInternalId, sectionId, field, rest)
                        }
                        println("Completed posting fields for section: ${section.label}")
                    } else {
                        println("Error: No sectionId assigned for section: ${section.label}")
                    }
                }


                def templateSection = parsedData.details.templateSections?.find { it.label == "createFromTemplate" }
                println("templateSection :${templateSection}")
                if (templateSection) {
                    println("Processing template section: createFromTemplate")
                    templateSection.fields.each { field ->
                        String tsectionId = ""
                        tsectionId = templateSectionId?.toString()

                        postFieldToSection(copiedBlueprintInternalId, tsectionId, field, rest)
                    }
                    println("Completed posting fields for template section: createFromTemplate")
                } else {
                    println("No template section found for 'createFromTemplate'")
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

//def main() {
//    Sql sql
//    ClarityRestClient rest
//
//    try {
//        sql = getDBConnection()
//        rest = new ClarityRestClient("admin", sql.getConnection())
//
//        println("query: ")
//        String encodedData = request.getParameter("data")
//        def jsonSlurper = new JsonSlurper()
//        def parsedData = jsonSlurper.parseText(encodedData)
//
//        String existingInternalId = parsedData.blueprintId
//        String internalId = parsedData.standardBlueprintId
//        println("internalId : ${internalId}")
//
//        Map<String, Object> createdNewBlueprint
//        Map<String, Object> existingBlueprintData
//        Map<String, Object> createdBlueprintData
//        Map<String, Object> editedBlueprint
//
//
//        if (existingInternalId) {
//            println("Blueprint exists. Starting from edit operations...")
//            existingBlueprintData = getBlueprintById(existingInternalId, rest)
//            editedBlueprint = editBlueprint(existingBlueprintData.code, rest)
//            println("editedBlueprint Exist: ${editedBlueprint}")
//            if (existingBlueprintData.isEmpty()) {
//                LOG.error("Failed to retrieve existing blueprint by internalId: ${existingInternalId}")
//                return
//            }
//            println("Editing Existing Blueprint ID: ${existingInternalId}")
//
//        } else {
//            println("Blueprint does not exist. Creating new blueprint...")
//            createdNewBlueprint = createBlueprintByPost(internalId, parsedData.details.name, rest)
//            createdBlueprintData = getBlueprintById(createdNewBlueprint._internalId.toString(), rest)
//            editedBlueprint = editBlueprint(createdBlueprintData.code, rest)
//            println("Created Blueprint: ${createdNewBlueprint._internalId}")
//            println(createdBlueprintData.code)
//            println("editedBlueprintttt: ${editedBlueprint}")
//
//            if (createdNewBlueprint.isEmpty()) {
//                println("Failed to create blueprint.")
//                return
//            }
//        }
//
//        String copiedBlueprintInternalId = editedBlueprint._internalId.toString()
//        println("Blueprint ID for Operations: ${copiedBlueprintInternalId}")
//
//        // Proceed with further operations (assuming all methods handle the rest accordingly)
//        postModulesToBlueprint(copiedBlueprintInternalId, parsedData.modules, rest)
//        println("Posted modules")
//
//        postVisualsToBlueprint(copiedBlueprintInternalId, parsedData.visuals, rest)
//
//        deleteModulesNotInParsedList(copiedBlueprintInternalId, parsedData.visuals, rest)
//
//        def sectionsIds = getSectionsFromBlueprint(copiedBlueprintInternalId, rest)
//        println("Section IDs: ${sectionsIds}")
//
//        def sortedSections = sectionsIds.sort { a, b -> a.internalId <=> b.internalId }
//
//        def sectionMap = [
//                summary    : sortedSections[0]?.internalId,
//                template   : sortedSections[1]?.internalId,
//                stakeholder: sortedSections[2]?.internalId,
//                settings   : sortedSections[3]?.internalId
//        ]
//
//        parsedData.details.sections.each { section ->
//            println("Processing section: ${section.label}")
//            String sectionId = sectionMap[section.label.toLowerCase()] ?: sectionMap.settings
//
//            if (sectionId) {
//                section.fields.each { field ->
//                    println("Posting field: ${field}")
//                    postFieldToSection(copiedBlueprintInternalId, sectionId, field, rest)
//                }
//                println("Completed posting fields for section: ${section.label}")
//            } else {
//                println("Error: No sectionId assigned for section: ${section.label}")
//            }
//        }
//
//        def templateSection = parsedData.details.templateSections?.find { it.label == "createFromTemplate" }
//        if (templateSection) {
//            println("Processing template section: createFromTemplate")
//            templateSection.fields.each { field ->
//                postFieldToSection(copiedBlueprintInternalId, sectionMap.template, field, rest)
//            }
//            println("Completed posting fields for template section: createFromTemplate")
//        } else {
//            println("No template section found for 'createFromTemplate'")
//        }
//
//        parsedData.rules.each { rule ->
//            postRule(copiedBlueprintInternalId, rule, rest)
//        }
//        println("Rules posted")
//
//        Map<String, Object> putData = [mode: "PUBLISHED"]
//        updateBlueprint(copiedBlueprintInternalId, putData, rest)
//        println("Updated blueprint")
//
//    } catch (Exception e) {
//        println(e.printStackTrace())
//    } finally {
//        rest?.close()
//        sql?.close()
//    }
//}
//
//main()
//
