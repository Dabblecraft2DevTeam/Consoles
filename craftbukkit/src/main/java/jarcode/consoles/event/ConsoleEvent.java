package jarcode.consoles.event;

import jarcode.consoles.internal.ConsoleComponent;

public class ConsoleEvent<T extends ConsoleComponent> {

	private final T context;

	public ConsoleEvent(T context) {
		this.context = context;
	}

	public T getContext() {
		return context;
	}
}