package games.cubi.utils.maps;

import java.util.ArrayList;
import java.util.Objects;
import java.util.function.BiConsumer;

// Will this ever be useful? Probably not but it was fun to write

/**
 * A multimap backed by a single array, designed for construction on one thread before being passed to another thread and iterated.
 */
public class ArrayMultiMap<K, V> {
    private static final ArrayMultiMap EMPTY = new ArrayMultiMap<>(null, Object.class, Object.class);

    private final Object[] backingArray;
    private final Class<K> keyClass;
    private final Class<V> valueClass;

    private ArrayMultiMap(Object[] array, Class<K> key, Class<V> value) {
        if (array != null && !(key.isInstance(array[0]))) throw new IllegalArgumentException("First value must be a key!");
        if (array != null) {
            for (Object object : array) {
                if (!key.isInstance(object) && !value.isInstance(object))
                    throw new IllegalArgumentException("All objects must be keys or values!");
            }
        }
        backingArray = array;
        keyClass = key;
        valueClass = value;
    }

    public void forEachKey(BiConsumer<K, V> consumer) {
        if (backingArray == null) return;

        K currentKey = (K) backingArray[0];

        for (int i = 1; i < backingArray.length; i++) {
            Object object = backingArray[i];
            if (keyClass.isInstance(object)) {
                currentKey = (K) object;
            } else {
                consumer.accept(currentKey, (V) object);
            }
        }
    }

    /**
     * The builder is designed for reuse after each {@link #build()} call, and does not hold any references to the built object after
     * construction, allowing it to be used from another thread. However, all calls to the same builder object must happen on one thread.
     * <p></p>
     * You must NEVER supply an object to this builder which is both a {@code K} and a {@code V}. Keys and values are distinguished
     * by checking if they are instances of the key and value classes respectively.
     */
    public static class Builder<K, V> {
        private final ArrayList<Object> backingList = new ArrayList<>();
        private final Class<K> keyClass;
        private final Class<V> valueClass;

        private Builder(Class<K> key, Class<V> value) {
            if (key.isAssignableFrom(value) || value.isAssignableFrom(key)) {
                throw new IllegalArgumentException("Key and value types must be distinct");
            }
            keyClass = key;
            valueClass = value;
        }

        public static <K, V> Builder<K, V> reusableOf(Class<K> key, Class<V> value) {
            return new Builder<>(key, value);
        }

        public void writeKey(K key) {
            backingList.add(Objects.requireNonNull(key));
        }

        public void writeValue(V value) {
            if (backingList.isEmpty()) throw new IllegalArgumentException("Cannot write value before corresponding key");
            backingList.add(Objects.requireNonNull(value));
        }

        public ArrayMultiMap<K, V> build() {
            if (backingList.isEmpty()) return EMPTY;
            ArrayMultiMap<K,V> returnVal = new ArrayMultiMap<>(backingList.toArray(), keyClass, valueClass);
            backingList.clear();
            return returnVal;
        }
    }
}
