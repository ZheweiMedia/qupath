package qupath.lib.classifiers.gui;

import ij.ImagePlus;
import ij.ImageStack;
import ij.plugin.filter.ThresholdToSelection;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.javacpp.indexer.UByteIndexer;
import org.bytedeco.javacpp.indexer.UShortIndexer;
import org.bytedeco.javacpp.opencv_core;
import org.bytedeco.javacpp.opencv_core.Mat;
import org.bytedeco.javacpp.opencv_core.MatVector;
import org.bytedeco.javacpp.opencv_core.Scalar;
import org.bytedeco.javacpp.opencv_core.Size;
import org.bytedeco.javacpp.opencv_imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.annotations.JsonAdapter;

import qupath.imagej.objects.ROIConverterIJ;
import qupath.lib.classifiers.pixel.PixelClassifier;
import qupath.lib.classifiers.pixel.PixelClassifierMetadata;
import qupath.lib.classifiers.pixel.PixelClassifierMetadata.OutputType;
import qupath.lib.classifiers.pixel.features.OpenCVFeatureCalculator;
import qupath.lib.common.ColorTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.TileRequest;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.classes.PathClassFactory;
import qupath.lib.objects.classes.Reclassifier;
import qupath.lib.objects.classes.PathClassFactory.PathClasses;
import qupath.lib.objects.helpers.PathObjectTools;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.AreaROI;
import qupath.lib.roi.PathROIToolsAwt;
import qupath.lib.roi.PathROIToolsAwt.CombineOp;
import qupath.lib.roi.interfaces.PathArea;
import qupath.lib.roi.interfaces.PathShape;
import qupath.lib.roi.interfaces.ROI;
import qupath.opencv.processing.TypeAdaptersCV;

import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * User interface for interacting with pixel classification.
 * 
 * @author Pete Bankhead
 *
 */
public class PixelClassifierStatic {

    private static final Logger logger = LoggerFactory.getLogger(PixelClassifierStatic.class);

    
    /**
     * Generate a QuPath ROI by thresholding an image channel image.
     * 
     * @param img the input image (any type)
     * @param minThreshold minimum threshold; pixels &geq; minThreshold will be included
     * @param maxThreshold maximum threshold; pixels &leq; maxThreshold will be included
     * @param band the image band to threshold (channel)
     * @param request a {@link RegionRequest} corresponding to this image, used to calibrate the coordinates.  If null, 
     * 			we assume no downsampling and an origin at (0,0).
     * @return
     * 
     * @see #thresholdToROI(ImageProcessor, TileRequest)
     */
    public static ROI thresholdToROI(BufferedImage img, double minThreshold, double maxThreshold, int band, RegionRequest request) {
    	int w = img.getWidth();
    	int h = img.getHeight();
    	float[] pixels = new float[w * h];
    	img.getRaster().getSamples(0, 0, w, h, band, pixels);
    	var fp = new FloatProcessor(w, h, pixels);
    	
    	fp.setThreshold(minThreshold, maxThreshold, ImageProcessor.NO_LUT_UPDATE);
    	return thresholdToROI(fp, request);
    }
    
    /**
     * Generate a QuPath ROI by thresholding an image channel image, deriving coordinates from a TileRequest.
     * <p>
     * This can give a more accurate result than depending on a RegionRequest because it is possible to avoid some loss of precision.
     * 
     * @param raster
     * @param minThreshold
     * @param maxThreshold
     * @param band
     * @param request
     * @return
     * 
     * @see #thresholdToROI(ImageProcessor, RegionRequest)
     */
    public static ROI thresholdToROI(Raster raster, double minThreshold, double maxThreshold, int band, TileRequest request) {
    	int w = raster.getWidth();
    	int h = raster.getHeight();
    	float[] pixels = new float[w * h];
    	raster.getSamples(0, 0, w, h, band, pixels);
    	var fp = new FloatProcessor(w, h, pixels);
    	
    	fp.setThreshold(minThreshold, maxThreshold, ImageProcessor.NO_LUT_UPDATE);
    	return thresholdToROI(fp, request);
    }
    
    
    
    /**
     * Generate a QuPath ROI from an ImageProcessor.
     * <p>
     * It is assumed that the ImageProcessor has had its min and max threshold values set.
     * 
     * @param ip
     * @param request
     * @return
     */
    static ROI thresholdToROI(ImageProcessor ip, RegionRequest request) {
    	// Need to check we have any above-threshold pixels at all
    	int n = ip.getWidth() * ip.getHeight();
    	boolean noPixels = true;
    	double min = ip.getMinThreshold();
    	double max = ip.getMaxThreshold();
    	for (int i = 0; i < n; i++) {
    		double val = ip.getf(i);
    		if (val >= min && val <= max) {
    			noPixels = false;
    			break;
    		}
    	}
    	if (noPixels)
    		return null;
    	    	
    	// Generate a shape, using the RegionRequest if we can
    	var roiIJ = new ThresholdToSelection().convert(ip);
    	if (request == null)
    		return ROIConverterIJ.convertToPathROI(roiIJ, 0, 0, 1, -1, 0, 0);
    	return ROIConverterIJ.convertToPathROI(
    			roiIJ,
    			-request.getX()/request.getDownsample(),
    			-request.getY()/request.getDownsample(),
    			request.getDownsample(), -1, request.getZ(), request.getT());
    }
    
    static ROI thresholdToROI(ImageProcessor ip, TileRequest request) {
    	// Need to check we have any above-threshold pixels at all
    	int n = ip.getWidth() * ip.getHeight();
    	boolean noPixels = true;
    	double min = ip.getMinThreshold();
    	double max = ip.getMaxThreshold();
    	for (int i = 0; i < n; i++) {
    		double val = ip.getf(i);
    		if (val >= min && val <= max) {
    			noPixels = false;
    			break;
    		}
    	}
    	if (noPixels)
    		return null;
    	    	
    	// Generate a shape, using the TileRequest if we can
    	var roiIJ = new ThresholdToSelection().convert(ip);
    	if (request == null)
    		return ROIConverterIJ.convertToPathROI(roiIJ, 0, 0, 1, -1, 0, 0);
    	return ROIConverterIJ.convertToPathROI(
    			roiIJ,
    			-request.getTileX(),
    			-request.getTileY(),
    			request.getDownsample(), -1, request.getZ(), request.getT());
    }
    
    
    /**
     * Convert an OpenCV {@code Mat} into an ImageJ {@code ImagePlus}.
     * 
     * @param mat
     * @param title
     * @return
     */
    public static ImagePlus matToImagePlus(Mat mat, String title) {
        if (mat.channels() == 1) {
            return new ImagePlus(title, matToImageProcessor(mat));
        }
        MatVector matvec = new MatVector();
        opencv_core.split(mat, matvec);
        ImageStack stack = new ImageStack(mat.cols(), mat.rows());
        for (int s = 0; s < matvec.size(); s++) {
            stack.addSlice(matToImageProcessor(matvec.get(s)));
        }
        return new ImagePlus(title, stack);
    }

    /**
     * Convert a single-channel OpenCV {@code Mat} into an ImageJ {@code ImageProcessor}.
     * 
     * @param mat
     * @return
     */
    public static ImageProcessor matToImageProcessor(Mat mat) {
    	if (mat.channels() != 1)
    		throw new IllegalArgumentException("Only a single-channel Mat can be converted to an ImageProcessor! Specified Mat has " + mat.channels() + " channels");
        int w = mat.cols();
        int h = mat.rows();
        if (mat.depth() == opencv_core.CV_32F) {
            FloatIndexer indexer = mat.createIndexer();
            float[] pixels = new float[w*h];
            indexer.get(0L, pixels);
            return new FloatProcessor(w, h, pixels);
        } else if (mat.depth() == opencv_core.CV_8U) {
            UByteIndexer indexer = mat.createIndexer();
            int[] pixels = new int[w*h];
            indexer.get(0L, pixels);
            ByteProcessor bp = new ByteProcessor(w, h);
            for (int i = 0; i < pixels.length; i++)
            	bp.set(i, pixels[i]);
            return bp;
        } else if (mat.depth() == opencv_core.CV_16U) {
            UShortIndexer indexer = mat.createIndexer();
            int[] pixels = new int[w*h];
            indexer.get(0L, pixels);
            short[] shortPixels = new short[pixels.length];
            for (int i = 0; i < pixels.length; i++)
            	shortPixels[i] = (short)pixels[i];
            return new ShortProcessor(w, h, shortPixels, null); // TODO: Test!
        } else {
        	Mat mat2 = new Mat();
            mat.convertTo(mat2, opencv_core.CV_32F);
            ImageProcessor ip = matToImageProcessor(mat2);
            mat2.release();
            return ip;
        }
    }
    
    /**
     * Feature calculator that simply takes a square of neighboring pixels as the features.
     * <p>
     * Warning! This is far from complete and may well be removed.
     * <p>
     * Note also that it only extends BasicFeatureCalculator because that is required by the OpenCVPixelClassifier... it shouldn't really.
     * 
     * @author Pete Bankhead
     *
     */
    @JsonAdapter(TypeAdaptersCV.OpenCVTypeAdaptorFactory.class)
    public static class ExtractNeighborsFeatureCalculator extends BasicFeatureCalculator {
    	
    	private int radius;
    	private PixelClassifierMetadata metadata;
    	private int[] inputChannels;
    	private int n;
    	
    	public ExtractNeighborsFeatureCalculator(String name, double pixelSizeMicrons, int radius, int...inputChannels) {
    		super(name, Collections.emptyList(), Collections.emptyList(), pixelSizeMicrons);
    		this.radius = radius;
			
    		inputChannels = new int[] {0, 1, 2};
    		
    		n = (radius * 2 + 1) * (radius * 2 + 1) * inputChannels.length;
			this.inputChannels = inputChannels;
					
    		var channels = IntStream.range(0, n)
    				.mapToObj(c -> ImageChannel.getInstance("Feature " + c, Integer.MAX_VALUE))
    				.toArray(ImageChannel[]::new);
    		metadata = new PixelClassifierMetadata.Builder()
    				.inputShape(256, 256)
    				.inputPixelSize(pixelSizeMicrons)
    				.channels(channels)
    				.build();
    	}

		@Override
		public Mat calculateFeatures(ImageServer<BufferedImage> server, RegionRequest request) throws IOException {
			var img = getPaddedRequest(server, request, radius);
			var raster = img.getRaster();
			
			n = (radius * 2 + 1) * (radius * 2 + 1) * inputChannels.length;
			
			var mat = new Mat(img.getHeight()-radius*2, img.getWidth()-radius*2, opencv_core.CV_32FC(n));
			
			int rows = mat.rows();
			int cols = mat.cols();
			
			FloatIndexer idx = mat.createIndexer();
			
			for (long r = 0; r < rows; r++) {
				for (long c = 0; c < cols; c++) {
					long k = 0;
					for (int b : inputChannels) {
						for (int y = (int)r; y < r + radius * 2 + 1; y++) {
							for (int x = (int)c; x < c + radius * 2 + 1; x++) {
								float val = raster.getSampleFloat(x, y, b);
								//								System.err.println(r + ", " + c + ", " + k);
								idx.put(r, c, k, val);
								k++;
							}							
						}
					}				
				}
			}
			idx.release();
			
//			matToImagePlus(mat, "Features").show();
			
			return mat;
		}

		@Override
		public PixelClassifierMetadata getMetadata() {
			return metadata;
		}
    	
    	
    }
    
    
    
    
    @JsonAdapter(TypeAdaptersCV.OpenCVTypeAdaptorFactory.class)
    public static class BasicFeatureCalculator implements OpenCVFeatureCalculator {
    	
    	private String name;
    	private List<Integer> channels = new ArrayList<>();
    	
    	@JsonAdapter(FeatureFilters.FeatureFilterTypeAdapterFactory.class)
    	private List<FeatureFilter> filters = new ArrayList<>();
    	private PixelClassifierMetadata metadata;
    	
    	private int nPyramidLevels = 1;
    	private int padding = 0;
    	
    	public BasicFeatureCalculator(String name, List<Integer> channels, List<FeatureFilter> filters, double pixelSizeMicrons) {
    		this.name = name;
    		this.channels.addAll(channels);
    		this.filters.addAll(filters);
    		
    		var outputChannels = new ArrayList<ImageChannel>();
    		for (var channel : channels) {
    			for (var filter : filters) {
    				outputChannels.add(ImageChannel.getInstance("Channel " + channel + ": " + filter.getName(), ColorTools.makeRGB(255, 255, 255)));
//    				outputChannels.add(new PixelClassifierOutputChannel(channel.getName() + ": " + filter.getName(), ColorTools.makeRGB(255, 255, 255)));
    			}
    		}
    		
    		padding = filters.stream().mapToInt(f -> f.getPadding()).max().orElseGet(() -> 0);
    		metadata = new PixelClassifierMetadata.Builder()
    				.channels(outputChannels)
    				.inputPixelSize(pixelSizeMicrons)
    				.inputShape(512, 512)
    				.build();
    		
    		
    		for (int i = 1; i< nPyramidLevels; i++) {
    			padding *= 2;
    		}
    		
    	}
    	
    	public String toString() {
    		return name;
    	}
    	
		@Override
		public Mat calculateFeatures(ImageServer<BufferedImage> server, RegionRequest request) throws IOException {
			
			BufferedImage img = PixelClassifierStatic.getPaddedRequest(server, request, padding);
			
			List<Mat> output = new ArrayList<opencv_core.Mat>();
			
			int w = img.getWidth();
			int h = img.getHeight();
			float[] pixels = new float[w * h];
			var mat = new Mat(h, w, opencv_core.CV_32FC1);
			FloatIndexer idx = mat.createIndexer();
			for (var channel : channels) {
				pixels = img.getRaster().getSamples(0, 0, w, h, channel, pixels);
//				channel.getValues(img, 0, 0, w, h, pixels);
				idx.put(0L, pixels);
				
				addFeatures(mat, output);
				
				if (nPyramidLevels > 1) {
					var matLastLevel = mat;
        			var size = mat.size();
	    			for (int i = 1; i < nPyramidLevels; i++) {
	    				// Downsample pyramid level
	    				var matPyramid = new Mat();
	    				opencv_imgproc.pyrDown(matLastLevel, matPyramid);
	    				// Add features to a temporary list (because we'll need to resize them
	    				var tempList = new ArrayList<Mat>();
	    				addFeatures(matPyramid, tempList);
	    				for (var temp : tempList) {
	    					// Upsample
	    					for (int k = i; k > 0; k--)
	    						opencv_imgproc.pyrUp(temp, temp);
	    					// Adjust size if necessary
	    					if (temp.rows() != size.height() || temp.cols() != size.width())
	    						opencv_imgproc.resize(temp, temp, size, 0, 0, opencv_imgproc.INTER_CUBIC);
	    					output.add(temp);
	    				}
	    				if (matLastLevel != mat)
	    					matLastLevel.release();
	    				matLastLevel = matPyramid;
	    			}
	    			matLastLevel.release();
				}
    			
			}
			
			opencv_core.merge(new MatVector(output.toArray(Mat[]::new)), mat);
			if (padding > 0)
				mat.put(mat.apply(new opencv_core.Rect(padding, padding, mat.cols()-padding*2, mat.rows()-padding*2)).clone());
			
			return mat;
		}
		
		
		void addFeatures(Mat mat, List<Mat> output) {
			for (var filter : filters) {
				filter.calculate(mat, output);
			}
	    }
		

		@Override
		public PixelClassifierMetadata getMetadata() {
			return metadata;
		}
    	
    }
    
        
    
    



	/**
	 * Get a raster, padded by the specified amount, to the left, right, above and below.
	 * <p>
	 * Note that the padding is defined in terms of the <i>destination</i> pixels.
	 * <p>
	 * In other words, a specified padding of 5 should actually result in 20 pixels being added in each dimension 
	 * if the {@code request.getDownsample() == 4}.
	 * <p>
	 * Currently, zero-padding is used.
	 * 
	 * @param server
	 * @param request
	 * @param padding
	 * @return
	 * @throws IOException 
	 */
	public static BufferedImage getPaddedRequest(ImageServer<BufferedImage> server, RegionRequest request, int padding) throws IOException {
		// If we don't have any padding, just return directly
		if (padding == 0)
			return server.readBufferedImage(request);
		// If padding < 0, throw an exception
		if (padding < 0)
			new IllegalArgumentException("Padding must be >= 0, but here it is " + padding);
		// Get the expected bounds
		double downsample = request.getDownsample();
		int x = (int)(request.getX() - padding * downsample);
		int y = (int)(request.getY() - padding * downsample);
		int x2 = (int)((request.getX() + request.getWidth()) + padding * downsample);
		int y2 = (int)((request.getY() + request.getHeight()) + padding * downsample);
		// If we're out of range, we'll need to work a bit harder
		int padLeft = 0, padRight = 0, padUp = 0, padDown = 0;
		boolean outOfRange = false;
		if (x < 0) {
			padLeft = (int)Math.round(-x/downsample);
			x = 0;
			outOfRange = true;
		}
		if (y < 0) {
			padUp = (int)Math.round(-y/downsample);
			y = 0;
			outOfRange = true;
		}
		if (x2 > server.getWidth()) {
			padRight  = (int)Math.round((x2 - server.getWidth() - 1)/downsample);
			x2 = server.getWidth();
			outOfRange = true;
		}
		if (y2 > server.getHeight()) {
			padDown  = (int)Math.round((y2 - server.getHeight() - 1)/downsample);
			y2 = server.getHeight();
			outOfRange = true;
		}
		// If everything is within range, this should be relatively straightforward
		RegionRequest request2 = RegionRequest.createInstance(request.getPath(), downsample, x, y, x2-x, y2-y, request.getZ(), request.getT());
		BufferedImage img = server.readBufferedImage(request2);
		if (outOfRange) {
			WritableRaster raster = img.getRaster();
			WritableRaster rasterPadded = raster.createCompatibleWritableRaster(
					raster.getWidth() + padLeft + padRight,
					raster.getHeight() + padUp + padDown);
			rasterPadded.setRect(padLeft, padUp, raster);
			// Add padding above
			if (padUp > 0) {
				WritableRaster row = raster.createWritableChild(0, 0, raster.getWidth(), 1, 0, 0, null);
				for (int r = 0; r < padUp; r++)
					rasterPadded.setRect(padLeft, r, row);
			}
			// Add padding below
			if (padDown > 0) {
				WritableRaster row = raster.createWritableChild(0, raster.getHeight()-1, raster.getWidth(), 1, 0, 0, null);
				for (int r = padUp + raster.getHeight(); r < rasterPadded.getHeight(); r++)
					rasterPadded.setRect(padLeft, r, row);
			}
			// Add padding to the left
			if (padLeft > 0) {
				WritableRaster col = rasterPadded.createWritableChild(padLeft, 0, 1, rasterPadded.getHeight(), 0, 0, null);
				for (int c = 0; c < padLeft; c++)
					rasterPadded.setRect(c, 0, col);
			}
			// Add padding to the right
			if (padRight > 0) {
				WritableRaster col = rasterPadded.createWritableChild(rasterPadded.getWidth()-padRight-1, 0, 1, rasterPadded.getHeight(), 0, 0, null);
				for (int c = padLeft + raster.getWidth(); c < rasterPadded.getWidth(); c++)
					rasterPadded.setRect(c, 0, col);
			}
			// TODO: The padding seems to work - but something to be cautious with...
			img = new BufferedImage(img.getColorModel(), rasterPadded, img.isAlphaPremultiplied(), null);
		}
		return img;
	}

	/**
	     * Compute local standard deviation
	     * 
	     * @param mat
	     * @param size
	     * @return
	     */
	    public static Mat localStandardDeviation(final Mat mat, final Size size) {
	    	// Create a normalized kernel
	    	Mat strel = opencv_imgproc.getStructuringElement(opencv_imgproc.MORPH_ELLIPSE, size);
	    	strel.convertTo(strel, opencv_core.CV_64F);
	    	Scalar sum = opencv_core.sumElems(strel);
	    	opencv_core.multiplyPut(strel, 1.0/sum.get());
	    	sum = opencv_core.sumElems(strel);
	    	
	    	Mat matESquared = new Mat();
	    	mat.convertTo(matESquared, opencv_core.CV_64F);
	    	opencv_imgproc.filter2D(matESquared, matESquared, opencv_core.CV_64F, strel);
	    	opencv_core.multiply(matESquared, matESquared, matESquared);
	    	
	    	Mat matSquaredE = mat.clone();
	    	matSquaredE.convertTo(matSquaredE, opencv_core.CV_64F);
	    	opencv_core.multiply(matSquaredE, matSquaredE, matSquaredE);
	    	opencv_imgproc.filter2D(matSquaredE, matSquaredE, opencv_core.CV_64F, strel);
	    	
	    	opencv_core.subtract(matSquaredE, matESquared, matESquared);
	    	opencv_core.sqrt(matESquared, matESquared);
	    	
	    	matSquaredE.release();
	    	matESquared.convertTo(matESquared, mat.depth());
	    	return matESquared;
	    	
	//    	Mat matESquared = new Mat();
	//    	opencv_imgproc.blur(mat, matESquared, size);
	//    	opencv_core.multiply(matESquared, matESquared, matESquared);
	//    	
	//    	Mat matSquaredE = new Mat();
	//    	opencv_core.multiply(mat, mat, matSquaredE);
	//    	opencv_imgproc.blur(matSquaredE, matSquaredE, size);
	//    	
	//    	opencv_core.subtract(matESquared, matSquaredE, matESquared);
	//    	
	//    	matSquaredE.release();
	//    	return matESquared;
	    }
	    
	    
	    /**
	     * Create detections objects via a pixel classifier.
	     * 
	     * @param imageData
	     * @param classifier
	     * @param selectedObject
	     * @param minSizePixels
	     * @param doSplit
	     * @return
	     */
    public static boolean createDetectionsFromPixelClassifier(
			ImageData<BufferedImage> imageData, PixelClassifier classifier, PathObject selectedObject, 
			double minSizePixels, boolean doSplit) {
		return createObjectsFromPixelClassifier(
				new PixelClassificationImageServer(imageData, classifier),
				selectedObject,
				(var roi) -> PathObjects.createDetectionObject(roi),
				minSizePixels, doSplit);
	}

    /**
     * Create annotation objects via a pixel classifier.
     * 
     * @param imageData
     * @param classifier
     * @param selectedObject
     * @param minSizePixels
     * @param doSplit
     * @return
     */
	public static boolean createAnnotationsFromPixelClassifier(
			ImageData<BufferedImage> imageData, PixelClassifier classifier, PathObject selectedObject, 
			double minSizePixels, boolean doSplit) {
		
		return createObjectsFromPixelClassifier(
				new PixelClassificationImageServer(imageData, classifier),
				selectedObject,
				(var roi) -> {
					var annotation = PathObjects.createAnnotationObject(roi);
					annotation.setLocked(true);
					return annotation;
				},
				minSizePixels, doSplit);
	}

	/**
	 * Create objects & add them to an object hierarchy based on thresholding the output of a pixel classifier.
	 * 
	 * @param server
	 * @param selectedObject
	 * @param creator
	 * @param doSplit
	 * @return
	 */
	public static boolean createObjectsFromPixelClassifier(
			PixelClassificationImageServer server, PathObject selectedObject, 
			Function<ROI, ? extends PathObject> creator, double minSizePixels, boolean doSplit) {
		
		var hierarchy = server.getImageData().getHierarchy();
		var classifier = server.getClassifier();
	
		var clipArea = selectedObject == null ? null : PathROIToolsAwt.getArea(selectedObject.getROI());
		Collection<TileRequest> tiles;
		if (selectedObject == null) {
			tiles = server.getAllTileRequests();
		} else {
			var request = RegionRequest.createInstance(
					server.getPath(), server.getDownsampleForResolution(0), 
					selectedObject.getROI());			
			tiles = server.getTiles(request);
		}
	
		Map<PathClass, List<PathObject>> pathObjectMap = tiles.parallelStream().map(t -> {
			var list = new ArrayList<PathObject>();
			try {
				var img = server.readBufferedImage(t.getRegionRequest());
				var nChannels = classifier.getMetadata().getChannels().size();
				// Get raster containing classifications and integer values, by taking the argmax
				var raster = img.getRaster();
				if (classifier.getMetadata().getOutputType() != OutputType.Classification) {
					int h = raster.getHeight();
					int w = raster.getWidth();
					byte[] output = new byte[w * h];
					for (int y = 0; y < h; y++) {
						for (int x = 0; x < w; x++) {
							int maxInd = 0;
							float maxVal = raster.getSampleFloat(x, y, 0);
							for (int c = 1; c < nChannels; c++) {
								float val = raster.getSampleFloat(x, y, c);						
								if (val > maxVal) {
									maxInd = c;
									maxVal = val;
								}
								output[y*w+x] = (byte)maxInd;
							}
						}
					}
					raster = WritableRaster.createPackedRaster(
							new DataBufferByte(output, w*h), w, h, 8, null);
				}
				for (int c = 0; c < nChannels; c++) {
					String name = server.getChannelName(c);
					if (name == null || server.getDefaultChannelColor(c) == null)
						continue;
					var pathClass = PathClassFactory.getPathClass(name);
					if (pathClass == PathClassFactory.getDefaultPathClass(PathClasses.IGNORE))
						continue;
					ROI roi = thresholdToROI(raster, c-0.5, c+0.5, 0, t);
										
					if (roi != null && clipArea != null) {
						var roiArea = PathROIToolsAwt.getArea(roi);
						PathROIToolsAwt.combineAreas(roiArea, clipArea, CombineOp.INTERSECT);
						if (roiArea.isEmpty())
							roi = null;
						else
							roi = PathROIToolsAwt.getShapeROI(roiArea, roi.getC(), roi.getZ(), roi.getT());
					}
					
					if (roi != null)
						list.add(PathObjects.createDetectionObject(roi, pathClass));
				}
			} catch (Exception e) {
				logger.error("Error requesting classified tile", e);
			}
			return list;
		}).flatMap(p -> p.stream()).collect(Collectors.groupingBy(p -> p.getPathClass(), Collectors.toList()));
	
		// Merge objects with the same classification
		var pathObjects = new ArrayList<PathObject>();
		for (var entry : pathObjectMap.entrySet()) {
			var pathClass = entry.getKey();
			var list = entry.getValue();
			Path2D path = new Path2D.Double();
			for (var pathObject : list) {
				var shape = PathROIToolsAwt.getShape(pathObject.getROI());
				path.append(shape, false);
			}
	
			var plane = ImagePlane.getDefaultPlane();
			var roi = PathROIToolsAwt.getShapeROI(path, plane.getC(), plane.getZ(), plane.getT(), 0.5);
			
			// Apply size threshold
			if (roi != null && minSizePixels > 0) {
				if (roi instanceof AreaROI)
					roi = (PathShape)PathROIToolsAwt.removeSmallPieces((AreaROI)roi, minSizePixels, minSizePixels);
				else if (!(roi instanceof PathArea && ((PathArea)roi).getArea() > minSizePixels))
					continue;
			}
	
			
			if (doSplit) {
				var rois = PathROIToolsAwt.splitROI(roi);
				for (var r : rois) {
					var annotation = creator.apply(r);
					annotation.setPathClass(pathClass);
					pathObjects.add(annotation);
				}
			} else {
				var annotation = creator.apply(roi);
				annotation.setPathClass(pathClass);
				pathObjects.add(annotation);				
			}
		}
	
		// Add objects
		if (selectedObject == null) {
			hierarchy.clearAll();
			hierarchy.getRootObject().addPathObjects(pathObjects);
			hierarchy.fireHierarchyChangedEvent(PixelClassifierStatic.class);
		} else {
			((PathAnnotationObject)selectedObject).setLocked(true);
			selectedObject.clearPathObjects();
			selectedObject.addPathObjects(pathObjects);
			hierarchy.fireHierarchyChangedEvent(PixelClassifierStatic.class, selectedObject);
		}
		return true;
	}


	/**
	 * Apply classification from a server to a collection of objects.
	 * 
	 * @param server
	 * @param pathObjects
	 */
	public static void classifyObjects(PixelClassificationImageServer server, Collection<PathObject> pathObjects) {
		var reclassifiers = pathObjects.parallelStream().map(p -> {
				try {
					var roi = PathObjectTools.getROI(p, true);
					int x = (int)Math.round(roi.getCentroidX());
					int y = (int)Math.round(roi.getCentroidY());
					int ind = server.getClassification(x, y, roi.getZ(), roi.getT());
					return new Reclassifier(p, PathClassFactory.getPathClass(server.getChannelName(ind)), false);
				} catch (Exception e) {
					return new Reclassifier(p, null, false);
				}
			}).collect(Collectors.toList());
		reclassifiers.parallelStream().forEach(r -> r.apply());
		server.getImageData().getHierarchy().fireObjectClassificationsChangedEvent(server, pathObjects);
	}
	
	
	public static void classifyCells(ImageData<BufferedImage> imageData, PixelClassifier classifier) {
		classifyObjects(imageData, classifier, imageData.getHierarchy().getCellObjects());
	}

	public static void classifyDetections(ImageData<BufferedImage> imageData, PixelClassifier classifier) {
		classifyObjects(imageData, classifier, imageData.getHierarchy().getDetectionObjects());
	}
	
	public static void classifyObjects(ImageData<BufferedImage> imageData, PixelClassifier classifier, Collection<PathObject> pathObjects) {
		classifyObjects(new PixelClassificationImageServer(imageData, classifier), pathObjects);
	}
    
    
    


}