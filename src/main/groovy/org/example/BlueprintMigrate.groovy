import de.itdesign.clarity.BlueprintMigration
import de.itdesign.clarity.rest.ClarityRestClient
import groovy.json.JsonSlurper

def blueprintMigration = new BlueprintMigration()

def sql = blueprintMigration.getDBConnection()
def rest = new ClarityRestClient("admin", sql.getConnection())

try {
    String encodedData = request.getParameter("data")
    String methodData = request.getParameter("method")
    def jsonSlurper = new JsonSlurper()
    def parsedData = jsonSlurper.parseText(encodedData)

    String existingInternalId = parsedData.blueprintId
    String internalId = parsedData.standardBlueprintId
    String name = parsedData.details.name

    if (existingInternalId && methodData?.equalsIgnoreCase("update")) {

        if (existingInternalId == internalId) {
            println("Default blueprint can't be updated.")
        } else {
            Map<String, Object> existingBlueprintData = blueprintMigration.getBlueprintById(existingInternalId, rest)
            if (existingBlueprintData.isEmpty()) {
                println("Failed to retrieve existing blueprint by internalId: ${existingInternalId}")
            } else {
                String blueprintCode = existingBlueprintData.code

                def editedBlueprint = blueprintMigration.editBlueprint(blueprintCode.toString(), rest)
                String copiedBlueprintInternalId = editedBlueprint._internalId.toString()

                blueprintMigration.deleteExistingModules(copiedBlueprintInternalId, rest)

                blueprintMigration.postModulesToBlueprint(copiedBlueprintInternalId, parsedData.modules, rest)

                blueprintMigration.postVisualsToBlueprint(copiedBlueprintInternalId, parsedData.visuals, rest)
                blueprintMigration.deleteExistingFields(copiedBlueprintInternalId, rest)

                def sectionIdsMap = blueprintMigration.deleteExistingFields(copiedBlueprintInternalId, rest)

                parsedData.details.sections.each { section ->
                    blueprintMigration.processSectionFields(copiedBlueprintInternalId, section, sectionIdsMap, rest)
                }
                blueprintMigration.processTemplateSection(copiedBlueprintInternalId, parsedData.details.templateSections, sectionIdsMap, rest)

                blueprintMigration.deleteMatchingRules(copiedBlueprintInternalId, rest)

                parsedData.rules.each { rule ->
                    blueprintMigration.postRule(copiedBlueprintInternalId, rule, rest)
                }

                Map<String, Object> putData = [
                        mode: "PUBLISHED"
                ]

                blueprintMigration.updateBlueprint(copiedBlueprintInternalId, putData, rest)
                println("Successfully updated blueprint!")

            }
        }
    } else {
        Map<String, Object> createdBlueprint = blueprintMigration.createBlueprintByPost(internalId, parsedData.details.name, rest)
        if (createdBlueprint.isEmpty()) {
            println("Failed to create blueprint.")
        } else {
            String createdBlueprintInternalId = createdBlueprint._internalId.toString()
            println("Created blueprint Id: ${createdBlueprintInternalId}")

            Map<String, Object> createdBlueprintData = blueprintMigration.getBlueprintById(createdBlueprintInternalId, rest)
            if (createdBlueprintData.isEmpty()) {
                println("Failed to retrieve created blueprint by internalId: ${createdBlueprintInternalId}")
            } else {
                String blueprintCode = createdBlueprintData.code

                Map<String, Object> editedBlueprint = blueprintMigration.editBlueprint(blueprintCode, rest)
                String copiedBlueprintInternalId = editedBlueprint._internalId.toString()

                blueprintMigration.deleteExistingModules(copiedBlueprintInternalId, rest)

                blueprintMigration.postModulesToBlueprint(copiedBlueprintInternalId, parsedData.modules, rest)

                blueprintMigration.postVisualsToBlueprint(copiedBlueprintInternalId, parsedData.visuals, rest)
                blueprintMigration.deleteExistingFields(copiedBlueprintInternalId, rest)

                def sectionIdsMap = blueprintMigration.deleteExistingFields(copiedBlueprintInternalId, rest)

                parsedData.details.sections.each { section ->
                    blueprintMigration.processSectionFields(copiedBlueprintInternalId, section, sectionIdsMap, rest)
                }

                blueprintMigration.processTemplateSection(copiedBlueprintInternalId, parsedData.details.templateSections, sectionIdsMap, rest)

                parsedData.rules.each { rule ->
                    def ruleId = rule._internalId?.toString()?.isInteger() ? rule._internalId.toInteger() : 0
                    if (ruleId >= 5000000) {
                        blueprintMigration.postRule(copiedBlueprintInternalId, rule, rest)
                    } else {

                    }
                }
                Map<String, Object> putData = [
                        mode: "PUBLISHED"
                ]

                blueprintMigration.updateBlueprint(copiedBlueprintInternalId, putData, rest)

                println("Successfully created blueprint!")
            }
        }
    }
} catch (Exception e) {
    println(e.printStackTrace())
} finally {
    rest?.close()
    sql?.close()
}