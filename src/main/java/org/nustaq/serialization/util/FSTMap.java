package org.nustaq.serialization.util;

/**
 * Created by ruedi on 15.06.2015.
 */
public class FSTMap<K, V> {

    private static final int[] prim = FSTObject2IntMap.prim;

    private static final int GROFAC = 2;

    private static int adjustSize(int size) {
        for (int i = 0; i < prim.length - 1; i++) {
            if (size < prim[i]) {
                return prim[i];
            }
        }
        return size;
    }

    private Object mKeys[];
    private Object mValues[];
    private int mNumberOfElements;
    private FSTMap<K, V> next;
    private final boolean checkClazzOnEquals = false;

    public FSTMap(int initialSize) {
        if (initialSize < 2) {
            initialSize = 2;
        }

        initialSize = adjustSize(initialSize * 2);

        mKeys = new Object[initialSize];
        mValues = new Object[initialSize];
        mNumberOfElements = 0;
    }

    final public void put(K key, V value) {
        int hash = key.hashCode() & 0x7FFFFFFF;
        putHash(key, value, hash, this);
    }

    private void putHash(K key, V value, int hash, FSTMap<K, V> parent) {
        if (mNumberOfElements * GROFAC > mKeys.length) {
            if (parent != null) {
                if ((parent.mNumberOfElements + mNumberOfElements) * GROFAC > parent.mKeys.length) {
                    parent.resize(parent.mKeys.length * GROFAC);
                    parent.put(key, value);
                    return;
                } else {
                    resize(mKeys.length * GROFAC);
                }
            } else {
                resize(mKeys.length * GROFAC);
            }
        }

        int idx = hash % mKeys.length;

        if (mKeys[idx] == null) // new
        {
            mNumberOfElements++;
            mValues[idx] = value;
            mKeys[idx] = key;
        } else if (mKeys[idx].equals(key) && (!checkClazzOnEquals || mKeys[idx].getClass() == key.getClass()))    // overwrite
        {
            mValues[idx] = value;
        } else {
            putNext(hash, key, value);
        }
    }

    private void putNext(final int hash, final K key, final V value) {
        if (next == null) {
            int newSiz = mNumberOfElements / 3;
            next = new FSTMap<>(newSiz);
        }
        next.putHash(key, value, hash, this);
    }

    final public V get(final K key) {
        final int hash = key.hashCode() & 0x7FFFFFFF;
        //return getHash(key,hash); inline =>
        final int idx = hash % mKeys.length;

        final Object mapsKey = mKeys[idx];
        if (mapsKey == null) // not found
        {
            return null;
        } else if (mapsKey.equals(key) && (!checkClazzOnEquals || mapsKey.getClass() == key.getClass()))  // found
        {
            return (V) mValues[idx];
        } else {
            if (next == null) {
                return null;
            }
            V res = next.getHash(key, hash);
            return res;
        }
        // <== inline
    }

    private V getHash(final K key, final int hash) {
        final int idx = hash % mKeys.length;

        final Object mapsKey = mKeys[idx];
        if (mapsKey == null) // not found
        {
            return null;
        } else if (mapsKey.equals(key) && (!checkClazzOnEquals || mapsKey.getClass() == key.getClass()))  // found
        {
            return (V) mValues[idx];
        } else {
            if (next == null) {
                return null;
            }
            V res = next.getHash(key, hash);
            return res;
        }
    }

    private void resize(int newSize) {
        newSize = adjustSize(newSize);
        Object[] oldTabKey = mKeys;
        Object[] oldTabVal = mValues;

        mKeys = new Object[newSize];
        mValues = new Object[newSize];
        mNumberOfElements = 0;

        for (int n = 0; n < oldTabKey.length; n++) {
            if (oldTabKey[n] != null) {
                put((K) oldTabKey[n], (V) oldTabVal[n]);
            }
        }
        if (next != null) {
            FSTMap oldNext = next;
            next = null;
            oldNext.rePut(this);
        }
    }

    private void rePut(FSTMap<K, V> kfstObject2IntMap) {
        for (int i = 0; i < mKeys.length; i++) {
            Object mKey = mKeys[i];
            if (mKey != null) {
                kfstObject2IntMap.put((K) mKey, (V) mValues[i]);
            }
        }
        if (next != null) {
            next.rePut(kfstObject2IntMap);
        }
    }
}
