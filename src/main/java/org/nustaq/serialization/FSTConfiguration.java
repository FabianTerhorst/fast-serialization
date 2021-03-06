/*
 * Copyright 2014 Ruediger Moeller.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.nustaq.serialization;

import org.nustaq.serialization.coders.FSTStreamDecoder;
import org.nustaq.serialization.coders.FSTStreamEncoder;
import org.nustaq.serialization.serializers.FSTArrayListSerializer;
import org.nustaq.serialization.serializers.FSTBigIntegerSerializer;
import org.nustaq.serialization.serializers.FSTBigNumberSerializers;
import org.nustaq.serialization.serializers.FSTClassSerializer;
import org.nustaq.serialization.serializers.FSTCollectionSerializer;
import org.nustaq.serialization.serializers.FSTDateSerializer;
import org.nustaq.serialization.serializers.FSTEnumSetSerializer;
import org.nustaq.serialization.serializers.FSTMapSerializer;
import org.nustaq.serialization.serializers.FSTStringBufferSerializer;
import org.nustaq.serialization.serializers.FSTStringBuilderSerializer;
import org.nustaq.serialization.serializers.FSTStringSerializer;
import org.nustaq.serialization.util.FSTUtil;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.SoftReference;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created with IntelliJ IDEA.
 * User: ruedi
 * Date: 18.11.12
 * Time: 20:41
 * <p>
 * Holds a serialization configuration/metadata.
 * Reuse this class !!! construction is very expensive. (just keep static instances around or use thread locals)
 */
public class FSTConfiguration {

    /**
     * if all attempts fail to find a class this guy is asked.
     * Can be used in case e.g. dynamic classes need get generated.
     */
    interface LastResortClassRessolver {
        Class getClass(String clName);
    }

    private final StreamCoderFactory streamCoderFactory = new FSTDefaultStreamCoderFactory(this);

    private String name;

    private final FSTClazzInfoRegistry serializationInfoRegistry = new FSTClazzInfoRegistry();
    private final HashMap<Class, List<SoftReference>> cachedObjects = new HashMap<>(97);
    private final FSTClazzNameRegistry classRegistry = new FSTClazzNameRegistry(null);
    boolean shareReferences = true;
    private volatile ClassLoader classLoader = getClass().getClassLoader();
    private boolean forceSerializable = false; // serialize objects which are not instanceof serializable using default serialization scheme.
    private final FSTClassInstantiator instantiator = new FSTDefaultClassInstantiator();

    private LastResortClassRessolver lastResortResolver;

    private boolean forceClzInit = false; // always execute default fields init, even if no transients

    // cache fieldinfo. This can be shared with derived FSTConfigurations in order to reduce footprint
    static class FieldKey {
        final Class clazz;
        final String fieldName;

        FieldKey(Class clazz, String fieldName) {
            this.clazz = clazz;
            this.fieldName = fieldName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            FieldKey fieldKey = (FieldKey) o;

            return clazz.equals(fieldKey.clazz) && fieldName.equals(fieldKey.fieldName);

        }

        @Override
        public int hashCode() {
            int result = clazz.hashCode();
            result = 31 * result + fieldName.hashCode();
            return result;
        }
    }

    final ConcurrentHashMap<FieldKey, FSTClazzInfo.FSTFieldInfo> fieldInfoCache;

    /**
     * debug helper
     *
     * @return
     */
    public String getName() {
        return name;
    }

    /**
     * debug helper
     *
     * @param name
     */
    public void setName(String name) {
        this.name = name;
    }

    // end cross platform stuff only
    /////////////////////////////////////

    private static final AtomicBoolean conflock = new AtomicBoolean(false);
    private static FSTConfiguration singleton;

    public static FSTConfiguration getDefaultConfiguration() {
        do {
        } while (!conflock.compareAndSet(false, true));
        try {
            if (singleton == null)
                singleton = createDefaultConfiguration();
            return singleton;
        } finally {
            conflock.set(false);
        }
    }

    /**
     * the standard FSTConfiguration.
     * - safe (no unsafe r/w)
     * - platform independent byte order
     * - moderate compression
     * <p>
     * note that if you are just read/write from/to byte arrays, its faster
     * to use DefaultCoder.
     * <p>
     * This should be used most of the time.
     *
     * @return
     */
    public static FSTConfiguration createDefaultConfiguration() {
        FSTConfiguration conf = new FSTConfiguration(null);
        return initDefaultFstConfigurationInternal(conf);
    }

    private static FSTConfiguration initDefaultFstConfigurationInternal(FSTConfiguration conf) {
        conf.addDefaultClazzes();
        // serializers
        FSTSerializerRegistry reg = conf.getCLInfoRegistry().getSerializerRegistry();
        reg.putSerializer(Class.class, new FSTClassSerializer(), false);
        reg.putSerializer(String.class, new FSTStringSerializer(), false);
        reg.putSerializer(Byte.class, new FSTBigNumberSerializers.FSTByteSerializer(), false);
        reg.putSerializer(Character.class, new FSTBigNumberSerializers.FSTCharSerializer(), false);
        reg.putSerializer(Short.class, new FSTBigNumberSerializers.FSTShortSerializer(), false);
        reg.putSerializer(Float.class, new FSTBigNumberSerializers.FSTFloatSerializer(), false);
        reg.putSerializer(Double.class, new FSTBigNumberSerializers.FSTDoubleSerializer(), false);

        reg.putSerializer(Date.class, new FSTDateSerializer(), false);
        reg.putSerializer(StringBuffer.class, new FSTStringBufferSerializer(), true);
        reg.putSerializer(StringBuilder.class, new FSTStringBuilderSerializer(), true);
        reg.putSerializer(EnumSet.class, new FSTEnumSetSerializer(), true);

        // for most cases don't register for subclasses as in many cases we'd like to fallback to JDK implementation
        // (e.g. TreeMap) in order to guarantee complete serialization
        reg.putSerializer(ArrayList.class, new FSTArrayListSerializer(), false);
        reg.putSerializer(Vector.class, new FSTCollectionSerializer(), false);
        reg.putSerializer(LinkedList.class, new FSTCollectionSerializer(), false); // subclass should register manually
        reg.putSerializer(HashSet.class, new FSTCollectionSerializer(), false); // subclass should register manually
        reg.putSerializer(HashMap.class, new FSTMapSerializer(), false); // subclass should register manually
        reg.putSerializer(LinkedHashMap.class, new FSTMapSerializer(), false); // subclass should register manually
        reg.putSerializer(Hashtable.class, new FSTMapSerializer(), true);
        reg.putSerializer(ConcurrentHashMap.class, new FSTMapSerializer(), true);

        // serializers for classes failing in fst JDK emulation (e.g. Android<=>JDK)
        reg.putSerializer(BigInteger.class, new FSTBigIntegerSerializer(), true);

        return conf;
    }

    /**
     * register a custom serializer for a given class or the class and all of its subclasses.
     * Serializers must be configured identical on read/write side and should be set before
     * actually making use of the Configuration.
     *
     * @param clazz
     * @param ser
     * @param alsoForAllSubclasses
     */
    public void registerSerializer(Class clazz, FSTObjectSerializer ser, boolean alsoForAllSubclasses) {
        serializationInfoRegistry.getSerializerRegistry().putSerializer(clazz, ser, alsoForAllSubclasses);
    }

    public boolean isForceClzInit() {
        return forceClzInit;
    }

    public LastResortClassRessolver getLastResortResolver() {
        return lastResortResolver;
    }

    public void setLastResortResolver(LastResortClassRessolver lastResortResolver) {
        this.lastResortResolver = lastResortResolver;
    }

    /**
     * always execute default fields init, even if no transients (so would get overwritten anyway)
     * required for lossy codecs (kson)
     *
     * @param forceClzInit
     * @return
     */
    public FSTConfiguration setForceClzInit(boolean forceClzInit) {
        this.forceClzInit = forceClzInit;
        return this;
    }

    FSTClassInstantiator getInstantiator(Class clazz) {
        return instantiator;
    }

    private FSTConfiguration(ConcurrentHashMap<FieldKey, FSTClazzInfo.FSTFieldInfo> sharedFieldInfos) {
        this.fieldInfoCache = sharedFieldInfos;
    }

    /**
     * reuse heavy weight objects. If a FSTStream is closed, objects are returned and can be reused by new stream instances.
     * the objects are held in soft references, so there should be no memory issues. FIXME: point of contention !
     *
     * @param cached
     */
    public void returnObject(Object cached) {
        try {
            while (!cacheLock.compareAndSet(false, true)) {
                // empty
            }
            List<SoftReference> li = cachedObjects.get(cached.getClass());
            if (li == null) {
                li = new ArrayList<>();
                cachedObjects.put(cached.getClass(), li);
            }
            if (li.size() < 5)
                li.add(new SoftReference(cached));
        } finally {
            cacheLock.set(false);
        }
    }

    /**
     * patch default serializer lookup. set to null to delete.
     * Should be set prior to any serialization going on (serializer lookup is cached).
     *
     * @param del
     */
    public void setSerializerRegistryDelegate(FSTSerializerRegistryDelegate del) {
        serializationInfoRegistry.setSerializerRegistryDelegate(del);
    }

    private final AtomicBoolean cacheLock = new AtomicBoolean(false);

    public Object getCachedObject(Class cl) {
        try {
            while (!cacheLock.compareAndSet(false, true)) {
                // empty
            }
            List<SoftReference> li = cachedObjects.get(cl);
            if (li == null) {
                return null;
            }
            for (int i = li.size() - 1; i >= 0; i--) {
                SoftReference softReference = li.get(i);
                Object res = softReference.get();
                li.remove(i);
                if (res != null) {
                    return res;
                }
            }
        } finally {
            cacheLock.set(false);
        }
        return null;
    }

    public boolean isForceSerializable() {
        return forceSerializable;
    }

    /**
     * treat unserializable classes same as if they would be serializable.
     *
     * @param forceSerializable //
     */
    public FSTConfiguration setForceSerializable(boolean forceSerializable) {
        this.forceSerializable = forceSerializable;
        return this;
    }

    public boolean isShareReferences() {
        return shareReferences;
    }

    /**
     * if false, identical objects will get serialized twice. Gains speed as long there are no double objects/cyclic references (typical for small snippets as used in e.g. RPC)
     * <p>
     * Cycles and Objects referenced more than once will not be detected (if set to false).
     * Additionally JDK compatibility is not supported (read/writeObject and stuff). Use case is highperformance
     * serialization of plain cycle free data (e.g. messaging). Can perform significantly faster (20-40%).
     *
     * @param shareReferences
     */
    public void setShareReferences(boolean shareReferences) {
        this.shareReferences = shareReferences;
    }

    /**
     * Preregister a class (use at init time). This avoids having to write class names.
     * Its a very simple and effective optimization (frequently > 2 times faster for small objects).
     * <p>
     * Read and write side need to have classes preregistered in the exact same order.
     * <p>
     * The list does not have to be complete. Just add your most frequently serialized classes here
     * to get significant gains in speed and smaller serialized representation size.
     */
    public void registerClass(Class... c) {
        for (int i = 0; i < c.length; i++) {
            classRegistry.registerClass(c[i], this);
            try {
                Class ac = Class.forName("[L" + c[i].getName() + ";");
                classRegistry.registerClass(ac, this);
            } catch (ClassNotFoundException e) {
                // silent
            }
        }
    }

    private void addDefaultClazzes() {
        classRegistry.registerClass(String.class, this);
        classRegistry.registerClass(Byte.class, this);
        classRegistry.registerClass(Short.class, this);
        classRegistry.registerClass(Integer.class, this);
        classRegistry.registerClass(Long.class, this);
        classRegistry.registerClass(Float.class, this);
        classRegistry.registerClass(Double.class, this);
        classRegistry.registerClass(BigDecimal.class, this);
        classRegistry.registerClass(BigInteger.class, this);
        classRegistry.registerClass(Character.class, this);
        classRegistry.registerClass(Boolean.class, this);
        classRegistry.registerClass(TreeMap.class, this);
        classRegistry.registerClass(HashMap.class, this);
        classRegistry.registerClass(ArrayList.class, this);
        classRegistry.registerClass(ConcurrentHashMap.class, this);
        classRegistry.registerClass(URL.class, this);
        classRegistry.registerClass(Date.class, this);
        classRegistry.registerClass(java.sql.Date.class, this);
        //classRegistry.registerClass(SimpleDateFormat.class,this);
        classRegistry.registerClass(TreeSet.class, this);
        classRegistry.registerClass(LinkedList.class, this);
        //classRegistry.registerClass(SimpleTimeZone.class,this);
        //classRegistry.registerClass(GregorianCalendar.class,this);
        classRegistry.registerClass(Vector.class, this);
        classRegistry.registerClass(Hashtable.class, this);
        classRegistry.registerClass(BitSet.class, this);
        classRegistry.registerClass(Locale.class, this);

        classRegistry.registerClass(StringBuffer.class, this);
        classRegistry.registerClass(StringBuilder.class, this);

        classRegistry.registerClass(Object.class, this);
        classRegistry.registerClass(Object[].class, this);
        classRegistry.registerClass(Object[][].class, this);
        classRegistry.registerClass(Object[][][].class, this);

        classRegistry.registerClass(byte[].class, this);
        classRegistry.registerClass(byte[][].class, this);

        classRegistry.registerClass(char[].class, this);
        classRegistry.registerClass(char[][].class, this);

        classRegistry.registerClass(short[].class, this);
        classRegistry.registerClass(short[][].class, this);

        classRegistry.registerClass(int[].class, this);
        classRegistry.registerClass(int[][].class, this);

        classRegistry.registerClass(float[].class, this);
        classRegistry.registerClass(float[][].class, this);

        classRegistry.registerClass(double[].class, this);
        classRegistry.registerClass(double[][].class, this);

        classRegistry.registerClass(long[].class, this);
        classRegistry.registerClass(long[][].class, this);

    }

    public FSTClazzNameRegistry getClassRegistry() {
        return classRegistry;
    }

    public FSTClazzInfoRegistry getCLInfoRegistry() {
        return serializationInfoRegistry;
    }

    public ClassLoader getClassLoader() {
        return classLoader;
    }

    /**
     * take the given array as input. the array is NOT copied.
     * <p>
     * WARNING: the input streams takes over ownership and might overwrite content
     * of this array in subsequent IO operations.
     *
     * @param arr
     * @return
     */
    public FSTObjectInput getObjectInput(byte arr[]) {
        return getObjectInput(arr, arr.length);
    }

    /**
     * take the given array as input. the array is NOT copied.
     * <p>
     * WARNING: the input streams takes over ownership and might overwrite content
     * of this array in subsequent IO operations.
     *
     * @param arr
     * @param len
     * @return
     */
    public FSTObjectInput getObjectInput(byte arr[], int len) {
        FSTObjectInput fstObjectInput = getIn();
        try {
            fstObjectInput.resetForReuseUseArray(arr, len);
            return fstObjectInput;
        } catch (IOException e) {
            FSTUtil.<RuntimeException>rethrow(e);
        }
        return null;
    }


    private FSTObjectInput getIn() {
        FSTObjectInput fstObjectInput = (FSTObjectInput) streamCoderFactory.getInput().get();
        if (fstObjectInput == null) {
            streamCoderFactory.getInput().set(new FSTObjectInput(this));
            return getIn();
        }
        fstObjectInput.conf = this;
        fstObjectInput.getCodec().setConf(this);
        return fstObjectInput;
    }

    private FSTObjectOutput getOut() {
        FSTObjectOutput fstOut = (FSTObjectOutput) streamCoderFactory.getOutput().get();
        if (fstOut == null || fstOut.closed) {
            streamCoderFactory.getOutput().set(new FSTObjectOutput(this));
            return getOut();
        }
        fstOut.conf = this;
        fstOut.getCodec().setConf(this);
        return fstOut;
    }

    /**
     * utility for thread safety and reuse. Do not close the resulting stream. However you should close
     * the given OutputStream 'out'
     *
     * @param out - can be null (temp bytearrays stream is created then)
     * @return
     */
    public FSTObjectOutput getObjectOutput(OutputStream out) {
        FSTObjectOutput fstObjectOutput = getOut();
        fstObjectOutput.resetForReUse(out);
        return fstObjectOutput;
    }

    /**
     * @return a recycled outputstream reusing its last recently used byte[] buffer
     */
    public FSTObjectOutput getObjectOutput() {
        return getObjectOutput(null);
    }

    /**
     * ignores all serialization related interfaces (Serializable, Externalizable) and serializes all classes using the
     * default scheme. Warning: this is a special mode of operation which fail serializing/deserializing many standard
     * JDK classes.
     *
     * @param ignoreSerialInterfaces
     */
    public void setStructMode(boolean ignoreSerialInterfaces) {
        serializationInfoRegistry.setStructMode(ignoreSerialInterfaces);
    }

    /**
     * special for structs
     *
     * @return
     */
    public boolean isStructMode() {
        return serializationInfoRegistry.isStructMode();
    }

    public interface StreamCoderFactory {
        FSTEncoder createStreamEncoder();

        FSTDecoder createStreamDecoder();

        ThreadLocal getInput();

        ThreadLocal getOutput();
    }

    public FSTEncoder createStreamEncoder() {
        return streamCoderFactory.createStreamEncoder();
    }

    public FSTDecoder createStreamDecoder() {
        return streamCoderFactory.createStreamDecoder();
    }

    /**
     * convenience
     */
    public Object asObject(byte b[]) {
        try {
            return getObjectInput(b).readObject();
        } catch (Exception e) {
            System.out.println("unable to decode:" + new String(b, 0, 0, Math.min(b.length, 100)));
            FSTUtil.<RuntimeException>rethrow(e);
        }
        return null;
    }

    /**
     * convenience. (object must be serializable)
     */
    public byte[] asByteArray(Object object) {
        FSTObjectOutput objectOutput = getObjectOutput();
        try {
            objectOutput.writeObject(object);
            return objectOutput.getCopyOfWrittenBuffer();
        } catch (IOException e) {
            FSTUtil.<RuntimeException>rethrow(e);
        }
        return null;
    }

    /**
     * Warning: avoids allocation + copying.
     * The returned byteArray is a direct pointer to underlying buffer.
     * the int length[] is expected to have at least on element.
     * The buffer can be larger than written data, therefore length[0] will contain written length.
     * <p>
     * The buffer content must be used (e.g. sent to network, copied to offheap) before doing another
     * asByteArray on the current Thread.
     */
    public byte[] asSharedByteArray(Object object, int length[]) {
        FSTObjectOutput objectOutput = getObjectOutput();
        try {
            objectOutput.writeObject(object);
            length[0] = objectOutput.getWritten();
            return objectOutput.getBuffer();
        } catch (IOException e) {
            FSTUtil.<RuntimeException>rethrow(e);
        }
        return null;
    }

    @Override
    public String toString() {
        return "FSTConfiguration{" +
                "name='" + name + '\'' +
                '}';
    }

    private static class FSTDefaultStreamCoderFactory implements FSTConfiguration.StreamCoderFactory {
        private final FSTConfiguration fstConfiguration;

        FSTDefaultStreamCoderFactory(FSTConfiguration fstConfiguration) {
            this.fstConfiguration = fstConfiguration;
        }

        @Override
        public FSTEncoder createStreamEncoder() {
            return new FSTStreamEncoder(fstConfiguration);
        }

        @Override
        public FSTDecoder createStreamDecoder() {
            return new FSTStreamDecoder(fstConfiguration);
        }

        static final ThreadLocal input = new ThreadLocal();
        static final ThreadLocal output = new ThreadLocal();

        @Override
        public ThreadLocal getInput() {
            return input;
        }

        @Override
        public ThreadLocal getOutput() {
            return output;
        }

    }
}
