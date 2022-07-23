/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.hoho.android.usbserial.util;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.InvalidParameterException;

/**
 * Clone of Android's HexDump class, for use in debugging. Cosmetic changes
 * only.
 */
public class HexDump {

    private final static char[] HEX_DIGITS = {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
    };

    public static String dumpHexString(byte[] array) {
        return dumpHexString(array, 0, array.length);
    }

    public static String dumpHexString(byte[] array, int offset, int length) {

        int consecutive_points = 3;
        int up_counter = 0;
        int down_counter = 0;

        int rise=0,down=0,peak=0, continuity_flag=0;

        int low_noise = 509;
        int high_noise = 513;
        int average_noise = (int) 0.5 * (low_noise + high_noise);
        int up_value = 0; // to detect second half of the first rise
        int down_value = 10000; // to detect second half of the first rise
        int slopes_count = 0;
        int neutron_count = 0;


        StringBuilder result = new StringBuilder();

        byte[] line = new byte[8];
        int lineIndex = 0;

        // AK 26.04.2022 length -> length-1
        // i++ -> i+=2 we need to concatenate two pairs of hex numbers

        for (int i = offset; i < offset + length - 1; i += 2) {

            StringBuilder signal = new StringBuilder();

            if (lineIndex == line.length) {
                //for (int j = 0; j < line.length; j++) {
                //    if (line[j] > ' ' && line[j] < '~') {
                //        result.append(new String(line, j, 1));
                //        //result.append(String.valueOf(j));
                //   } else {
                //        result.append(".");
                //    }
                //}

                //result.append("\n");
                lineIndex = 0;
            }

            byte b = array[i];
            //result.append(HEX_DIGITS[(b >>> 4) & 0x0F]);
            //result.append(HEX_DIGITS[b & 0x0F]);

            byte b1 = array[i];
            signal.append(HEX_DIGITS[(b1 >>> 4) & 0x0F]);
            signal.append(HEX_DIGITS[b1 & 0x0F]);

            byte b2 = array[i + 1];
            signal.append(HEX_DIGITS[(b2 >>> 4) & 0x0F]);
            signal.append(HEX_DIGITS[b2 & 0x0F]);

            int signal_decimal = Integer.parseInt(String.valueOf(signal), 16);

            result.append(String.valueOf(" "+signal_decimal));

            //if (signal_decimal < low_noise || signal_decimal > high_noise) {// To cut the noise

            // 03.06.2022
            // signal has 1) VERY fast fall in sig; 2) fast rise below and 3) slower rise above noise level;
            // 4) another even slower fall to the noise level
            // decided to work with 3) and 4) due to the bigger number of points,  will be enough?...
            // excluding signal artefact case
            // I'm still missing the over border signal case! with simple algorithm

            //result.append(String.valueOf(signal_decimal) + " ");

            // Notes from the ^previous and current versions
            // (2)+(3) long and steep rise
            // I think it is enough to detect this long rise across the noise level from below and above
            // because there is nothing left but to slowly relax back to the baseline unless it is an artefact
            // beside this fast rise is less prone to noise jitter during its progress
            // assume clean rise without a jitter
            if (signal_decimal<low_noise ) {

                if (signal_decimal >= up_value) {

                    up_value = signal_decimal;
                    result.append("^");
                    up_counter++;

                    if (up_counter >= consecutive_points) {
                        up_counter = -100;
                        result.append(" neutron ");
                    }

                }
                else {
                    up_counter = 1;
                    up_value = signal_decimal;
                    result.append("^");
                }
            }
            // Testing how data will fit to plot window
            //double min=340, max=580;
            //double window=100;
            //double resolution=window/(max-min);

            //int y= (int) (((double)signal_decimal-min)*resolution);
            //result.append(y);

            //result.append(signal_decimal);
            //result.append(" ");
            //}

            line[lineIndex++] = b;
        }

        return result.toString();
    }

    public static String toHexString(byte b) {
        return toHexString(toByteArray(b));
    }

    public static String toHexString(byte[] array) {
        return toHexString(array, 0, array.length);
    }

    public static String toHexString(byte[] array, int offset, int length) {
        char[] buf = new char[length * 2];

        int bufIndex = 0;
        for (int i = offset; i < offset + length; i++) {
            byte b = array[i];
            buf[bufIndex++] = HEX_DIGITS[(b >>> 4) & 0x0F];
            buf[bufIndex++] = HEX_DIGITS[b & 0x0F];
        }

        return new String(buf);
    }

    public static String toHexString(int i) {
        return toHexString(toByteArray(i));
    }

    public static String toHexString(short i) {
        return toHexString(toByteArray(i));
    }

    public static byte[] toByteArray(byte b) {
        byte[] array = new byte[1];
        array[0] = b;
        return array;
    }

    public static byte[] toByteArray(int i) {
        byte[] array = new byte[4];

        array[3] = (byte) (i & 0xFF);
        array[2] = (byte) ((i >> 8) & 0xFF);
        array[1] = (byte) ((i >> 16) & 0xFF);
        array[0] = (byte) ((i >> 24) & 0xFF);

        return array;
    }

    public static byte[] toByteArray(short i) {
        byte[] array = new byte[2];

        array[1] = (byte) (i & 0xFF);
        array[0] = (byte) ((i >> 8) & 0xFF);

        return array;
    }

    private static int toByte(char c) {
        if (c >= '0' && c <= '9')
            return (c - '0');
        if (c >= 'A' && c <= 'F')
            return (c - 'A' + 10);
        if (c >= 'a' && c <= 'f')
            return (c - 'a' + 10);

        throw new InvalidParameterException("Invalid hex char '" + c + "'");
    }

    public static byte[] hexStringToByteArray(String hexString) {
        int length = hexString.length();
        byte[] buffer = new byte[length / 2];

        for (int i = 0; i < length; i += 2) {
            buffer[i / 2] = (byte) ((toByte(hexString.charAt(i)) << 4) | toByte(hexString
                    .charAt(i + 1)));
        }

        return buffer;
    }
}
