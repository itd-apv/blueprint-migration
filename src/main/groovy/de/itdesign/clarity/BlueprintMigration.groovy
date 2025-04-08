package de.itdesign.clarity

import de.itdesign.clarity.rest.ClarityRestClient
import de.itdesign.clarity.rest.RestResponse
import org.apache.logging.log4j.LogManager
import com.niku.union.config.ConfigurationManager
import com.niku.union.config.properties.Database
import groovy.sql.Sql
import java.sql.DriverManager

class BlueprintMigration {
    static final LOG = LogManager.getLogger("BlueprintMigrate")

    Sql getDBConnection() {
        Sql sql
        try {
            Database database = ConfigurationManager.instance.properties.database.first()
            def connection = DriverManager.getConnection(database.url, database.username, database.password)
            sql = new Sql(connection)
        } catch (Exception e) {
            LOG.error("Database connection error", e)
        }
        return sql
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
            println("Error while fetching visualId from DB: ${e.message}", e)
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
        println("code: ${code}")
        Map<String, Object> body = [source: code, action: "edit"]
        try {
            resp = rest.POST(urlString, body)
            println("resp: ${resp.jsonMap()}")
        } catch (Exception e) {
            println("Error while editing blueprint: ${e.message}")
            e.printStackTrace()
            return [:]
        }
        resp?.jsonMap()
    }


    def deleteExistingModules(String blueprintInternalId, ClarityRestClient rest) {
        String urlString = "private/blueprints/${blueprintInternalId}/visuals"
        try {
            RestResponse resp = rest.GET(urlString)
            def existingModules = resp?.jsonMap()?._results ?: []
            println("exising modules: ${existingModules}")

            existingModules.each { module ->
                String moduleId = module._internalId
                String deleteUrl = "${urlString}/${moduleId}"
                println("delete url: ${deleteUrl}")

                try {
                    resp = rest.DELETE(deleteUrl)
                    println(resp.jsonMap())
                } catch (Exception e) {
                    println("Error deleting module with ID ${moduleId}: ${e.message}")
                }
            }
        } catch (Exception e) {
            println("Error while fetching existing modules: ${e.message}")
        }
    }


    def postModulesToBlueprint(String blueprintInternalId, List<Map<String, Object>> modules, ClarityRestClient rest) {
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
                    type         : visual.type,
                    sequence     : visual.sequence,
                    attributeName: visual.attributeName,
                    blueprintType: visual.blueprintType,
                    label        : visual.label,
                    visualId     : visual.visualId
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

    def deleteExistingFields(String blueprintId, ClarityRestClient rest) {
        def sectionIdsMap = [:]

        def blueprintDataUrl = "private/blueprintData/${blueprintId}"
        def blueprintDataResp = rest.GET(blueprintDataUrl)
        def blueprintData = blueprintDataResp?.jsonMap()?.details ?: [:]

        blueprintData.sections?.each { section ->
            println("Processing section: ${section.label}")
            String sectionId = section._internalId
            sectionIdsMap[section.label?.toLowerCase()] = sectionId

            section.fields?.each { field ->
                String fieldId = field._internalId
                String deleteUrl = "private/blueprints/${blueprintId}/sections/${sectionId}/fields/${fieldId}"
                try {
                    println("Deleting field: ${field.name} (ID: ${fieldId}) from section: ${section.label}")
                    rest.DELETE(deleteUrl)
                } catch (Exception e) {
                    println("Error deleting field ${fieldId}: ${e.message}")
                }
            }
        }

        blueprintData.templateSections?.each { section ->
            println("Processing template section: ${section.label}")
            String sectionId = section._internalId
            sectionIdsMap[section.label?.toLowerCase()] = sectionId

            section.fields?.each { field ->
                String fieldId = field._internalId
                String deleteUrl = "private/blueprints/${blueprintId}/sections/${sectionId}/fields/${fieldId}"
                try {
                    println("Deleting field: ${field.name} (ID: ${fieldId}) from template section: ${section.label}")
                    rest.DELETE(deleteUrl)
                } catch (Exception e) {
                    println("Error deleting field ${fieldId}: ${e.message}")
                }
            }
        }

        return sectionIdsMap
    }

    def postFieldToSection(String blueprintId, String sectionId, Map<String, Object> field, ClarityRestClient rest) {
        RestResponse resp
        String urlString = "private/blueprints/${blueprintId}/sections/${sectionId}/fields"
        println("Posting to URL: ${urlString}")

        try {
            Map<String, Object> formattedField = [
                    name       : field.name,
                    metadataURL: field.name,
                    column     : field.layout?.col ?: 0,
                    row        : field.layout?.row ?: 0,
                    width      : field.layout?.sizeX ?: 1,
                    height     : field.layout?.sizeY ?: 1
            ]
            resp = rest.POST(urlString, formattedField)
            println("Posted field: ${field.name}")
        } catch (Exception e) {
            println("Error while posting field: ${e.message}")
            e.printStackTrace()
            return [:]
        }
        return resp?.jsonMap()
    }

    def postRule(String copiedBlueprintInternalId, Map<String, Object> rule, ClarityRestClient rest) {
        RestResponse resp
        String urlString = "private/rules"

        Map<String, Object> postData = [
                name                : rule.name ?: "Default Name",
                isActive            : rule.isActive ?: false,
                category            : rule.category ?: "ui",
                associatedObjectCode: rule.associatedObjectCode ?: "odf_blueprint",
                description         : rule.description ?: "No description",
                associatedInstanceId: copiedBlueprintInternalId,
                objectCode          : rule.id ?: "project"
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

    def deleteMatchingRules(String targetAssociatedInstanceId, ClarityRestClient rest) {
        println("taregt: ${targetAssociatedInstanceId}")
        String urlString = "private/rules"
        def offset = 0
        def hasMore = true

        try {
            while (hasMore) {
                String url = "${urlString}?offset=${offset}"
                println("Fetching URL: ${url}")
                RestResponse response = rest.GET(url)
                def responseData = response?.jsonMap()
                def rules = responseData?._results ?: []
                hasMore = responseData?._next != null

                rules.each { rule ->
                    def ruleDetailUrl = "${urlString}/${rule._internalId}"
                    println("Rule Detail URL: ${ruleDetailUrl}")
                    RestResponse ruleDetailResp = rest.GET(ruleDetailUrl)
                    def ruleDetails = ruleDetailResp?.jsonMap()
                    println("Rule JSON: ${ruleDetails}")
                    println("associated Instance: ${ruleDetails?.associatedInstanceId}")


                    if (ruleDetails?.associatedInstanceId?.toString()?.trim() == targetAssociatedInstanceId?.toString()?.trim()) {
                        println("associated Instance inside: ${ruleDetails?.associatedInstanceId}")
                        deleteRule(rule._internalId.toString(), rest)
                        println("Deleted rule with ID: ${rule._internalId} (associatedInstanceId: ${targetAssociatedInstanceId})")
                    }
                }

                if (responseData?._next) {
                    offset += 25
                } else {
                    hasMore = false
                }
            }
        } catch (Exception e) {
            println("Error while deleting matching rules: ${e.message}")
            e.printStackTrace()
        }
    }

    def deleteRule(String ruleId, ClarityRestClient rest) {
        String urlString = "private/rules"
        def requestBody = [
                d: [
                        [_internalId: ruleId]
                ]
        ]

        try {
            RestResponse resp = rest.DELETE(urlString, requestBody)
            println("Deleted rule with ID: ${ruleId}")
            return resp?.jsonMap()
        } catch (Exception e) {
            println("Error deleting rule with ID ${ruleId}: ${e.message}")
            return [:]
        }
    }

    def processSectionFields(String blueprintId, def section, Map sectionIdsMap, ClarityRestClient rest) {
        String sectionLabel = section.label?.toLowerCase()
        String sectionId = sectionIdsMap[sectionLabel]

        if (sectionId) {
            println("Processing section: ${section.label}")
            section.fields.each { field ->
                postFieldToSection(blueprintId, sectionId, field, rest)
            }
            println("Completed posting fields for section: ${section.label}")
        } else {
            println("Error: No sectionId assigned for section: ${section.label}")
        }
    }

    def processTemplateSection(String blueprintId, def templateSections, Map sectionIdsMap, ClarityRestClient rest) {
        def templateSection = templateSections?.find { it.label?.toLowerCase() == "createfromtemplate" }

        if (templateSection) {
            String templateSectionId = sectionIdsMap[templateSection.label?.toLowerCase()]

            if (templateSectionId) {
                println("Processing template section: ${templateSection.label}")
                templateSection.fields.each { field ->
                    postFieldToSection(blueprintId, templateSectionId, field, rest)
                }
                println("Completed posting fields for template section: ${templateSection.label}")
            } else {
                println("Error: No sectionId assigned for template section: ${templateSection.label}")
            }
        } else {
            println("No template section found for 'createFromTemplate'")
        }
    }
}

