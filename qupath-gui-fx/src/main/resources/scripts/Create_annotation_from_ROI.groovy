/*
 * A script to create annotation object(s) having the same ROI as any
 * (other) currently selected object(s).
 */


import qupath.lib.objects.PathAnnotationObject
import qupath.lib.roi.RectangleROI
import qupath.lib.scripting.QP

// Set this to true to use the bounding box of the ROI, rather than the ROI itself
boolean useBoundingBox = false

// Get the current hierarchy
def hierarchy = QP.getCurrentHierarchy()

// Get the select objects
def selected = hierarchy.getSelectionModel().getSelectedObjects()

// Check we have anything to work with
if (selected.isEmpty()) {
    print("No objects selected!")
    return
}

// Loop through objects
def newAnnotations = new ArrayList<>()
for (def pathObject in selected) {

    // Unlikely to happen... but skip any objects not having a ROI
    if (!pathObject.hasROI()) {
        print("Skipping object without ROI: " + pathObject)
        continue
    }

    // Don't create a second annotation, unless we want a bounding box
    if (!useBoundingBox && pathObject.isAnnotation()) {
        print("Skipping annotation: " + pathObject)
        continue
    }

    // Create an annotation for whichever object is selected, with the same class
    // Note: because ROIs are (or should be) immutable, the same ROI is used here, rather than a duplicate
    def roi = pathObject.getROI()
    if (useBoundingBox)
        roi = new RectangleROI(
                roi.getBoundsX(),
                roi.getBoundsY(),
                roi.getBoundsWidth(),
                roi.getBoundsHeight(),
                roi.getC(),
                roi.getZ(),
                roi.getT())
    def annotation = new PathAnnotationObject(roi, pathObject.getPathClass())
    newAnnotations.add(annotation)
    print("Adding " + annotation)
}

// Actually add the objects
hierarchy.addPathObjects(newAnnotations, false)
if (newAnnotations.size() > 1)
    print("Added " + newAnnotations.size() + " annotation(s)")