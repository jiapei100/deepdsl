package deepdsl.cudnn;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import deepdsl.tensor.JTensor;
import deepdsl.tensor.JTensorFloat;
import deepdsl.util.ArithStats;
import jcuda.jcudnn.cudnnBatchNormMode; 
//import static jcuda.jcudnn.JCudnn.cudnnBatchNormalizationForwardInference;
import static jcuda.jcudnn.JCudnn.cudnnBatchNormalizationForwardTraining;
import static jcuda.jcudnn.JCudnn.cudnnBatchNormalizationBackward;

class RunningMeanVariance implements Serializable { 
	private static final long serialVersionUID = -8414099972220055277L;
	int forward_count;
	JTensorFloat mean, variance;
	int dim;

	public RunningMeanVariance(int dim) {
		this(dim, 0, JTensor.constFloat(0, dim), JTensor.constFloat(1, dim));
	}
	
	public RunningMeanVariance(int dim, int forward_count, JTensorFloat mean, JTensorFloat variance) {
		this.dim = dim;
		this.forward_count = forward_count;
		this.mean = mean;
		this.variance = variance;
	}
	
	public RunningMeanVariance load(String name) {
		RunningMeanVariance t = null;
		try {
			FileInputStream fileIn = new FileInputStream(name + ".ser");
			ObjectInputStream in = new ObjectInputStream(fileIn);
			t = (RunningMeanVariance) in.readObject();
			in.close();
			fileIn.close();
			if(t != null) {
				if(this.dim == t.dim) {
					System.out.printf("Restored %s\n", name);
					return t; 
				}
			}
		}
		catch(IOException i) {
			//			i.printStackTrace(); 
		}
		catch(ClassNotFoundException c) { 
			//			c.printStackTrace();  
		}
		return this;
	}
	public void save(String name) {
		try {
			FileOutputStream fileOut = new FileOutputStream(name + ".ser");
			ObjectOutputStream out = new ObjectOutputStream(fileOut);
			out.writeObject(this);
			out.close();
			fileOut.close();
			System.out.printf("Parameter is serialized in %s.ser\n", name);
		}
		catch(IOException i) {
			i.printStackTrace();
		}
	}
}

public class JCudnnBatchNorm extends JCudaFunction {
	static int mode = cudnnBatchNormMode.CUDNN_BATCHNORM_SPATIAL; // This is for convolution purpose
	static double epsilon = 1e-5; // I don't know what to pick so I picked the minimum.

	int forward_count = 0; 
	JCudnnDescriptor x_dptr, norm_dptr;  
	JCudaTensor running_mean, running_variance, saved_mean, saved_inv_variance;
	int[] x_dims, norm_dims; 
	String path;
	
	boolean trained = false;
	
	public JCudnnBatchNorm(String path, int[] x_dims) {
		this.x_dims = x_dims;
		this.path = path;
		this.x_dptr = new JCudnnDescriptor(x_dims);
		this.norm_dims = new int[] {1, x_dims[1], 1, 1};
		this.norm_dptr = new JCudnnDescriptor(norm_dims);
		
		RunningMeanVariance running = new RunningMeanVariance(x_dims[1]).load(path);
		running_mean = running.mean.asJCudaTensor();
		running_variance = running.variance.asJCudaTensor();
		forward_count = running.forward_count;
		 
		saved_mean = new JCudaTensor(norm_dims);
		saved_inv_variance = new JCudaTensor(norm_dims);
	}

	public void free() {
		if (trained) {
			new RunningMeanVariance(x_dims[1], forward_count, 
					running_mean.asJTensor(), running_variance.asJTensor())
			.save(path);
		}
		this.x_dptr.free();
		this.norm_dptr.free();
		this.running_mean.free();
		this.running_variance.free();
		this.saved_inv_variance.free();
		this.saved_mean.free();
	}

	public JCudaTensor forward_inference(JCudaTensor x, JCudaTensor scale, JCudaTensor bias) {
		JCudaTensor y = new JCudaTensor(x_dims);

		// FIXME: batch norm forward inference is incorrect! Don't know whether it is due to JCudnn or CUDNN itself.
		
//		int ret = cudnnBatchNormalizationForwardInference(cudnnHandle, mode, one, zero,
//				x_dptr.descriptor, x.getData(), x_dptr.descriptor, y.getData(),
//				norm_dptr.descriptor, scale.getData(), bias.getData(),
//				running_mean.getData(), running_variance.getData(), epsilon);
		
		double factor = 0; // don't change running mean or variance
				
		// Use forward training. A little slower but works. 
		int ret = cudnnBatchNormalizationForwardTraining(cudnnHandle, mode, one, zero, 
				x_dptr.descriptor, x.getData(), x_dptr.descriptor, y.getData(), 
				norm_dptr.descriptor, scale.getData(), bias.getData(), 
				factor, running_mean.getData(), running_variance.getData(), 
				epsilon, saved_mean.getData(), saved_inv_variance.getData());
		
		checkError(ret);

		return y;
	}

	public JCudaTensor forward(JCudaTensor x, JCudaTensor scale, JCudaTensor bias) {
		JCudaTensor y = new JCudaTensor(x_dims);

		double factor = 1.0/(1+forward_count++);

		long begin = System.nanoTime();

		int ret = cudnnBatchNormalizationForwardTraining(cudnnHandle, mode, one, zero, 
				x_dptr.descriptor, x.getData(), x_dptr.descriptor, y.getData(), 
				norm_dptr.descriptor, scale.getData(), bias.getData(), 
				factor, running_mean.getData(), running_variance.getData(), 
				epsilon, saved_mean.getData(), saved_inv_variance.getData()); 

		checkError(ret);

		ArithStats.cuda_timing("batch norm forward", begin);
		
		trained = true;

		return y;
	}

	public JCudaTensor[] backward(JCudaTensor dy, JCudaTensor x, JCudaTensor scale) {
		JCudaTensor d_scale = new JCudaTensor(norm_dims), d_bias = new JCudaTensor(norm_dims);
		JCudaTensor dx = dy; // new JCudaTensor(x.getDims()); // This is in-place. Forward call cannot be in-place since use x
		long begin = System.nanoTime();

		int ret = cudnnBatchNormalizationBackward(cudnnHandle, mode, one, zero, one, zero,
				x_dptr.descriptor, x.getData(), x_dptr.descriptor, dy.getData(), x_dptr.descriptor, dx.getData(),
				norm_dptr.descriptor, scale.getData(), d_scale.getData(), d_bias.getData(), 
				epsilon, saved_mean.getData(), saved_inv_variance.getData());

		checkError(ret);

		ArithStats.cuda_timing("batch norm backward", begin);

		return new JCudaTensor[]{dx, d_scale, d_bias};
	}
}
