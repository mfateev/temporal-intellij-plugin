package io.temporal.intellij.replay

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.ClassUtil

/**
 * Finds workflow implementations in the user's project using IntelliJ PSI.
 * Searches for classes that implement interfaces annotated with @WorkflowInterface.
 */
class WorkflowClassFinder(private val project: Project) {

    companion object {
        private const val WORKFLOW_INTERFACE_ANNOTATION = "io.temporal.workflow.WorkflowInterface"
        private const val WORKFLOW_METHOD_ANNOTATION = "io.temporal.workflow.WorkflowMethod"
    }

    /**
     * Represents a discovered workflow implementation.
     */
    data class WorkflowImplementation(
        val psiClass: PsiClass,
        val qualifiedName: String,
        val workflowTypeName: String,
        val interfaceClass: PsiClass
    )

    /**
     * Find all workflow implementations that match the given workflow type name.
     *
     * @param workflowTypeName The workflow type name from the history (e.g., "MyWorkflow")
     * @return List of matching implementations
     */
    fun findByWorkflowType(workflowTypeName: String): List<WorkflowImplementation> {
        val facade = JavaPsiFacade.getInstance(project)
        val projectScope = GlobalSearchScope.projectScope(project)
        val allScope = GlobalSearchScope.allScope(project)

        // Find @WorkflowInterface annotation class
        val workflowInterfaceAnnotation = facade.findClass(
            WORKFLOW_INTERFACE_ANNOTATION,
            allScope
        ) ?: return emptyList()

        val implementations = mutableListOf<WorkflowImplementation>()

        // Find all interfaces annotated with @WorkflowInterface
        val annotatedInterfaces = AnnotatedElementsSearch.searchPsiClasses(
            workflowInterfaceAnnotation,
            projectScope
        )

        for (workflowInterface in annotatedInterfaces) {
            if (!workflowInterface.isInterface) continue

            val typeName = extractWorkflowTypeName(workflowInterface)
            if (typeName != workflowTypeName) continue

            // Find all implementations of this interface in the project
            val inheritors = ClassInheritorsSearch.search(
                workflowInterface,
                projectScope,
                true // includeAnonymous
            )

            for (impl in inheritors) {
                // Skip interfaces - we want concrete implementations
                if (impl.isInterface) continue

                // Use ClassUtil.getJVMClassName to get the proper binary name with $ for inner classes
                val jvmClassName = ClassUtil.getJVMClassName(impl) ?: continue

                implementations.add(
                    WorkflowImplementation(
                        psiClass = impl,
                        qualifiedName = jvmClassName,
                        workflowTypeName = typeName,
                        interfaceClass = workflowInterface
                    )
                )
            }
        }

        return implementations
    }

    /**
     * Find all workflow implementations in the project (for browsing).
     *
     * @return List of all workflow implementations
     */
    fun findAllImplementations(): List<WorkflowImplementation> {
        val facade = JavaPsiFacade.getInstance(project)
        val projectScope = GlobalSearchScope.projectScope(project)
        val allScope = GlobalSearchScope.allScope(project)

        val workflowInterfaceAnnotation = facade.findClass(
            WORKFLOW_INTERFACE_ANNOTATION,
            allScope
        ) ?: return emptyList()

        val implementations = mutableListOf<WorkflowImplementation>()

        val annotatedInterfaces = AnnotatedElementsSearch.searchPsiClasses(
            workflowInterfaceAnnotation,
            projectScope
        )

        for (workflowInterface in annotatedInterfaces) {
            if (!workflowInterface.isInterface) continue

            val typeName = extractWorkflowTypeName(workflowInterface)

            val inheritors = ClassInheritorsSearch.search(
                workflowInterface,
                projectScope,
                true
            )

            for (impl in inheritors) {
                if (impl.isInterface) continue
                val jvmClassName = ClassUtil.getJVMClassName(impl) ?: continue

                implementations.add(
                    WorkflowImplementation(
                        psiClass = impl,
                        qualifiedName = jvmClassName,
                        workflowTypeName = typeName,
                        interfaceClass = workflowInterface
                    )
                )
            }
        }

        return implementations
    }

    /**
     * Extract the workflow type name from a @WorkflowInterface annotated interface.
     * The type name is determined by:
     * 1. The "name" attribute of @WorkflowMethod annotation (if specified)
     * 2. Otherwise, the simple name of the interface
     */
    private fun extractWorkflowTypeName(workflowInterface: PsiClass): String {
        // Check @WorkflowMethod annotations on methods for custom name
        for (method in workflowInterface.methods) {
            val workflowMethodAnnotation = method.getAnnotation(WORKFLOW_METHOD_ANNOTATION)
            if (workflowMethodAnnotation != null) {
                val customName = getAnnotationStringValue(workflowMethodAnnotation, "name")
                if (!customName.isNullOrEmpty()) {
                    return customName
                }
            }
        }

        // Default to interface simple name
        return workflowInterface.name ?: "Unknown"
    }

    /**
     * Get a string value from an annotation attribute.
     */
    private fun getAnnotationStringValue(annotation: PsiAnnotation, attributeName: String): String? {
        val attributeValue = annotation.findAttributeValue(attributeName) ?: return null

        return when (attributeValue) {
            is PsiLiteralExpression -> {
                val value = attributeValue.value
                if (value is String) value else null
            }
            else -> {
                // Handle other expression types by evaluating the text
                val text = attributeValue.text
                // Remove quotes if present
                if (text.startsWith("\"") && text.endsWith("\"")) {
                    text.substring(1, text.length - 1)
                } else {
                    text
                }
            }
        }
    }
}
