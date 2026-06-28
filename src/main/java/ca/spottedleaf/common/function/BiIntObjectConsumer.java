package ca.spottedleaf.common.function;

@FunctionalInterface
public interface BiIntObjectConsumer<V> {

    public void accept(final int key, final V value);

}
