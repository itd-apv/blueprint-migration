import de.itdesign.clarity.BlueprintMigration
import de.itdesign.clarity.rest.ClarityRestClient
import groovy.json.JsonSlurper
import org.apache.logging.log4j.LogManager

LOG = LogManager.getLogger("BlueprintMigrate")

def blueprintMigration = new BlueprintMigration()

def sql = blueprintMigration.getDBConnection()
def rest = new ClarityRestClient("admin", sql.getConnection())

    try {
        String encodedData = request.getParameter("data")
        def jsonSlurper = new JsonSlurper()
        def parsedData = jsonSlurper.parseText(encodedData)

        String existingInternalId = parsedData.blueprintId

        String internalId = parsedData.standardBlueprintId
        println("internalId : ${internalId}")

        String name = parsedData.details.name
        println("name: ${name}")

        if (existingInternalId) {
            println("Existing Blueprint ID found: ${existingInternalId}")

            // Use existingInternalId for all operations
            Map<String, Object> existingBlueprintData = blueprintMigration.getBlueprintById(existingInternalId, rest)
            if (existingBlueprintData.isEmpty()) {
                println("Failed to retrieve existing blueprint by internalId: ${existingInternalId}")
            } else {
                String blueprintCode = existingBlueprintData.code
                println("Blueprint Code: ${blueprintCode}")

                def editedBlueprint = blueprintMigration.editBlueprint(blueprintCode.toString(), rest)
                String copiedBlueprintInternalId = editedBlueprint._internalId.toString()
                println("Copied Blueprint ID: ${copiedBlueprintInternalId}")
                blueprintMigration.deleteExistingModules(copiedBlueprintInternalId, rest)

                blueprintMigration.postModulesToBlueprint(copiedBlueprintInternalId, parsedData.modules, rest)
                println("Posted modules")

                blueprintMigration.postVisualsToBlueprint(copiedBlueprintInternalId, parsedData.visuals, rest)
                blueprintMigration.deleteExistingFields(copiedBlueprintInternalId, rest)

                def sectionIdsMap = blueprintMigration.deleteExistingFields(copiedBlueprintInternalId, rest)
                println("Section IDs Map: ${sectionIdsMap}")


                parsedData.details.sections.each { section ->
                    blueprintMigration.processSectionFields(copiedBlueprintInternalId, section, sectionIdsMap, rest)
                }
                blueprintMigration.processTemplateSection(copiedBlueprintInternalId, parsedData.details.templateSections, sectionIdsMap, rest)


                blueprintMigration.deleteMatchingRules(copiedBlueprintInternalId, rest)

                parsedData.rules.each { rule ->
                    blueprintMigration.postRule(copiedBlueprintInternalId, rule, rest)
                }
                println("rules posted")

                Map<String, Object> putData = [
                        mode: "PUBLISHED"
                ]

                blueprintMigration.updateBlueprint(copiedBlueprintInternalId, putData, rest)
                println("updated blueprint")

            }
        } else {
            println("No existing blueprint ID found. Proceeding to create new blueprint.")
            Map<String, Object> createdBlueprint = blueprintMigration.createBlueprintByPost(internalId, parsedData.details.name, rest)
            if (createdBlueprint.isEmpty()) {
                println("Failed to create blueprint.")
            } else {
                String createdBlueprintInternalId = createdBlueprint._internalId.toString()
                println("Created Blueprint ID: ${createdBlueprintInternalId}")

                Map<String, Object> createdBlueprintData = blueprintMigration.getBlueprintById(createdBlueprintInternalId, rest)
                if (createdBlueprintData.isEmpty()) {
                    LOG.error("Failed to retrieve created blueprint by internalId: ${createdBlueprintInternalId}")
                } else {
                    String blueprintCode = createdBlueprintData.code
                    println("Blueprint Code: ${blueprintCode}")

                    Map<String, Object> editedBlueprint = blueprintMigration.editBlueprint(blueprintCode, rest)
                    String copiedBlueprintInternalId = editedBlueprint._internalId.toString()
                    println("Copied Blueprint ID: ${copiedBlueprintInternalId}")
                    blueprintMigration.deleteExistingModules(copiedBlueprintInternalId, rest)

                    blueprintMigration.postModulesToBlueprint(copiedBlueprintInternalId, parsedData.modules, rest)
                    println("Posted modules")

                    blueprintMigration.postVisualsToBlueprint(copiedBlueprintInternalId, parsedData.visuals, rest)
                    blueprintMigration.deleteExistingFields(copiedBlueprintInternalId, rest)

                    def sectionIdsMap = blueprintMigration.deleteExistingFields(copiedBlueprintInternalId, rest)
                    println("Section IDs Map: ${sectionIdsMap}")

                    parsedData.details.sections.each { section ->
                        blueprintMigration.processSectionFields(copiedBlueprintInternalId, section, sectionIdsMap, rest)
                    }

                    blueprintMigration.processTemplateSection(copiedBlueprintInternalId, parsedData.details.templateSections, sectionIdsMap, rest)
                    parsedData.rules.each { rule ->
                        blueprintMigration.postRule(copiedBlueprintInternalId, rule, rest)
                    }
                    println("rules posted")

                    Map<String, Object> putData = [
                            mode: "PUBLISHED"
                    ]

                    blueprintMigration.updateBlueprint(copiedBlueprintInternalId, putData, rest)
                    println("updated blueprint")
                }
            }
        }
    } catch (Exception e) {
        println(e.printStackTrace())
    } finally {
        rest?.close()
        sql?.close()
    }

