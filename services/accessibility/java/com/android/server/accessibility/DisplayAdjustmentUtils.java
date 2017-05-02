/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.accessibility;

import android.content.ContentResolver;
import android.content.Context;
import android.opengl.Matrix;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Slog;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Utility methods for performing accessibility display adjustments.
 */
class DisplayAdjustmentUtils {
    private static final String LOG_TAG = DisplayAdjustmentUtils.class.getSimpleName();

    /** Matrix and offset used for converting color to gray-scale. */
    private static final float[] GRAYSCALE_MATRIX = new float[] {
        .2126f, .2126f, .2126f, 0,
        .7152f, .7152f, .7152f, 0,
        .0722f, .0722f, .0722f, 0,
             0,      0,      0, 1
    };

    /** Matrix and offset used for value-only display inversion. */
    private static final float[] INVERSION_MATRIX_VALUE_ONLY = new float[] {
           -1, 0, 0, 0,
            0, -1, 0, 0,
            0, 0, -1, 0,
            1, 1, 1, 1
    };

    /** Default inversion mode for display color correction. */
    private static final int DEFAULT_DISPLAY_DALTONIZER =
            AccessibilityManager.DALTONIZER_CORRECT_DEUTERANOMALY;

    /**
     * Returns whether the specified user with has any display color
     * adjustments.
     */
    public static boolean hasAdjustments(Context context, int userId) {
        final ContentResolver cr = context.getContentResolver();

        if (Settings.Secure.getIntForUser(cr,
                Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED, 0, userId) != 0) {
            return true;
        }

        if (Settings.Secure.getIntForUser(cr,
                Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED, 0, userId) != 0) {
            return true;
        }

        return false;
    }
    
    private static  WindowManager mWindowManager = null;
    
    private static class AdjustmentThread extends Thread {
    	public boolean stopped = false;
    	
    	public void run() {
    		while (!stopped) {
    			try {
    				Display mDisplay = mWindowManager.getDefaultDisplay();
    				DisplayMetrics mDisplayMetrics = new DisplayMetrics();
    				mDisplay.getRealMetrics(mDisplayMetrics);
    				
    				float[] dims = {mDisplayMetrics.widthPixels, mDisplayMetrics.heightPixels};

    		        // Take the screenshot
    		        Bitmap mScreenBitmap = SurfaceControl.screenshot((int) dims[0], (int) dims[1]);
    		        
    		        if (mScreenBitmap != null) {
    		        	int width = mScreenBitmap.getWidth();
    		        	int height = mScreenBitmap.getHeight();
    		        	int totalPixels = (width / 32) * (height / 32);
    		        	
    		        	float avgRed = 0, avgGreen = 0, avgBlue = 0;
    		        	for (int i = 0; i < width; i += 32) {
    		        		for (int j = 0; j < height; j += 32) {
    		        			int color = mScreenBitmap.getPixel(i, j);
    		        			int red = Color.red(color), green = Color.green(color), blue = Color.blue(color);
    		        			avgRed += red; avgGreen += green; avgBlue += blue;
    		        		}
    		        	}
    		        	
    		        	avgRed /= totalPixels; avgGreen /= totalPixels; avgBlue /= totalPixels;
    		        	double kP = calculatePower(avgRed, avgGreen, avgBlue);
    		        	double kPReverse = calculatePower(255 - avgRed, 255 - avgGreen, 255 - avgBlue);
    		        	
    		        	Log.i("lzl", "reverse: " + (kP < kPReverse ? "false" : "true"));
    		        	
    		        	if (kP < kPReverse) {
    		        		setColorTransform(null);
    		        	} else {
    		        		setColorTransform(INVERSION_MATRIX_VALUE_ONLY);
    		        	}
    		        	
    		        	Log.i("lzl", "color transformed");
    		        }
    		        
//    		        if (mScreenBitmap != null) {
//    		        	int width = mScreenBitmap.getWidth();
//    		        	int height = mScreenBitmap.getHeight();
//    		        	int totalPixels = (width / 32) * (height / 32);
//    		        	
//    		        	int[][] redValues = new int[width][height];
//    		        	int[][] greenValues = new int[width][height];
//    		        	int[][] blueValues = new int[width][height];
//    		        	
//    		        	for (int i = 0; i < width; i += 32) {
//    		        		for (int j = 0; j < height; j += 32) {
//    		        			int color = mScreenBitmap.getPixel(i, j);
//    		        			redValues[i][j] = Color.red(color);
//    		        			greenValues[i][j] = Color.green(color);
//    		        			blueValues[i][j] = Color.blue(color);
//    		        		}
//    		        	}
//    		        	
//    		        	Log.i("lzl", "color values stored");
//    		        	
//    		        	float offset = 0;
//    		        	boolean reverse = false;
//    		        	double minP = Double.MAX_VALUE;
//    		        	for (int k = 0; k <= 255; k += 8) {
//    		        		float avgRed = 0, avgGreen = 0, avgBlue = 0;
//    		        		for (int i = 0; i < width; i += 32) {
//    		        			for (int j = 0; j < height; j += 32) {
//    		        				int red = redValues[i][j] + k, green = greenValues[i][j] + k, blue = blueValues[i][j] + k;
//    		        				red = red < 256 ? red : red - 256;
//    		        				green = green < 256 ? green : green - 256;
//    		        				blue = blue < 256 ? blue : blue - 256;
//    		        				avgRed += red; avgGreen += green; avgBlue += blue;
//    		        			}
//    		        		}
//    		        		avgRed /= totalPixels; avgGreen /= totalPixels; avgBlue /= totalPixels;
//    		        		double kP = calculatePower(avgRed, avgGreen, avgBlue);
//    		        		if (kP < minP) {
//    		        			minP = kP;
//    		        			offset = (float) k;
//    		        			reverse = false;
//    		        		}
//    		        		double kPReverse = calculatePower(255 - avgRed, 255 - avgGreen, 255 - avgBlue);
//    		        		if (kPReverse < minP) {
//    		        			minP = kPReverse;
//    		        			offset = (float) k;
//    		        			reverse = true;
//    		        		}
//    		        	}
//    		        	
//    		        	Log.i("lzl", "color transform matrix calculated");
//    		        	
//    		        	offset /= 255;
//    		        	
//    		        	Log.i("lzl", "offset: " + offset);
//    		        	Log.i("lzl", "reverse: " + (reverse ? "true" : "false"));
//    		        	
//    		        	float[] matrix = null;
//    		        	if (!reverse) {
//    		        		matrix = new float[] {
//    	    		        		1, 0, 0, 0,
//    	    		        		0, 1, 0, 0,
//    	    		        		0, 0, 1, 0,
//    	    		        		offset, offset, offset, 1
//    	    		        	};
//    		        	} else {
//    		        		matrix = new float[] {
//    	    		        		-1, 0, 0, 0,
//    	    		        		0, -1, 0, 0,
//    	    		        		0, 0, -1, 0,
//    	    		        		1-offset, 1-offset, 1-offset, 1
//    	    		        	};
//    		        	}
//		        		setColorTransform(matrix);
//    		        	
//    		        	Log.i("lzl", "color transformed");
//    		        }
    				
    		        Thread.sleep(5000);
    		        
    			} catch (InterruptedException e) {
    				
    			}
    		}
    	}
    }
    
    private static double power_screen_full_black = 86;
    private static double power_screen_full_red = 116;
    private static double power_screen_full_green = 203;
    private static double power_screen_full_blue = 187;
    
    private static double calculatePower(float avgRed, float avgGreen, float avgBlue) {
    	double avgRedL = avgRed / 255, avgGreenL = avgGreen / 255, avgBlueL = avgBlue / 255;
    	avgRedL = avgRedL <= 0.04045 ? avgRedL / 12.92 : Math.pow((avgRedL + 0.055) / 1.055, 2.4);
    	avgGreenL = avgGreenL <= 0.04045 ? avgGreenL / 12.92 : Math.pow((avgGreenL + 0.055) / 1.055, 2.4);
    	avgBlueL = avgBlueL <= 0.04045 ? avgBlueL / 12.92 : Math.pow((avgBlueL + 0.055) / 1.055, 2.4);
    	
    	return power_screen_full_black + avgRedL * power_screen_full_red + avgGreenL * power_screen_full_green + avgBlueL * power_screen_full_blue;
    }
    
    private static AdjustmentThread mAdjustmentThread = null;

    /**
     * Applies the specified user's display color adjustments.
     */
    public static void applyAdjustments(Context context, int userId) {
        final ContentResolver cr = context.getContentResolver();
        float[] colorMatrix = null;
        
        if (mWindowManager == null) {
        	mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        }
       
        if (Settings.Secure.getIntForUser(cr,
                Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED, 0, userId) != 0) {
//            colorMatrix = multiply(colorMatrix, INVERSION_MATRIX_VALUE_ONLY);
            
            mAdjustmentThread = new AdjustmentThread();
            mAdjustmentThread.start();
        } else {
        	if (mAdjustmentThread != null)
        		mAdjustmentThread.stopped = true;
        }

        if (Settings.Secure.getIntForUser(cr,
                Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED, 0, userId) != 0) {
            final int daltonizerMode = Settings.Secure.getIntForUser(cr,
                    Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER, DEFAULT_DISPLAY_DALTONIZER,
                    userId);
            // Monochromacy isn't supported by the native Daltonizer.
            if (daltonizerMode == AccessibilityManager.DALTONIZER_SIMULATE_MONOCHROMACY) {
                colorMatrix = multiply(colorMatrix, GRAYSCALE_MATRIX);
                setDaltonizerMode(AccessibilityManager.DALTONIZER_DISABLED);
            } else {
                setDaltonizerMode(daltonizerMode);
            }
        } else {
            setDaltonizerMode(AccessibilityManager.DALTONIZER_DISABLED);
        }

        setColorTransform(colorMatrix);
    }

    private static float[] multiply(float[] matrix, float[] other) {
        if (matrix == null) {
            return other;
        }
        float[] result = new float[16];
        Matrix.multiplyMM(result, 0, matrix, 0, other, 0);
        return result;
    }

    /**
     * Sets the surface flinger's Daltonization mode. This adjusts the color
     * space to correct for or simulate various types of color blindness.
     *
     * @param mode new Daltonization mode
     */
    private static void setDaltonizerMode(int mode) {
        try {
            final IBinder flinger = ServiceManager.getService("SurfaceFlinger");
            if (flinger != null) {
                final Parcel data = Parcel.obtain();
                data.writeInterfaceToken("android.ui.ISurfaceComposer");
                data.writeInt(mode);
                flinger.transact(1014, data, null, 0);
                data.recycle();
            }
        } catch (RemoteException ex) {
            Slog.e(LOG_TAG, "Failed to set Daltonizer mode", ex);
        }
    }

    /**
     * Sets the surface flinger's color transformation as a 4x4 matrix. If the
     * matrix is null, color transformations are disabled.
     *
     * @param m the float array that holds the transformation matrix, or null to
     *            disable transformation
     */
    private static void setColorTransform(float[] m) {
        try {
            final IBinder flinger = ServiceManager.getService("SurfaceFlinger");
            if (flinger != null) {
                final Parcel data = Parcel.obtain();
                data.writeInterfaceToken("android.ui.ISurfaceComposer");
                if (m != null) {
                    data.writeInt(1);
                    for (int i = 0; i < 16; i++) {
                        data.writeFloat(m[i]);
                    }
                } else {
                    data.writeInt(0);
                }
                flinger.transact(1015, data, null, 0);
                data.recycle();
            }
        } catch (RemoteException ex) {
            Slog.e(LOG_TAG, "Failed to set color transform", ex);
        }
    }

}
