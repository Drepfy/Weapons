package io.github.drepfy.vigil.discord;

import io.github.drepfy.vigil.moderation.RemoteStaff;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * A command sender for a Discord staff member: runs the normal punishment commands
 * and collects what they answer, so the answer can be shown on Discord. Built as a
 * proxy so it works with every server version's {@link CommandSender}.
 */
final class RemoteSender {

    private RemoteSender() {
    }

    /**
     * @param staffName shown as the staff member, e.g. {@code "Bob (Discord)"}
     * @param output    receives every message sent to it
     */
    static CommandSender create(String staffName, List<String> output) {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "sendMessage", "sendRawMessage" -> {
                    if (args != null) {
                        for (Object arg : args) {
                            if (arg instanceof String text) {
                                output.add(text);
                            } else if (arg instanceof String[] lines) {
                                output.addAll(List.of(lines));
                            }
                        }
                    }
                    return null;
                }
                case "getName", "staffName" -> {
                    return staffName;
                }
                case "hasPermission", "isPermissionSet" -> {
                    // Discord staff roles were checked before the command ran.
                    return true;
                }
                case "getServer" -> {
                    return Bukkit.getServer();
                }
                case "equals" -> {
                    return args != null && args.length == 1 && proxy == args[0];
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "toString" -> {
                    return "RemoteSender[" + staffName + "]";
                }
                default -> {
                    return defaultValue(method.getReturnType());
                }
            }
        };
        return (CommandSender) Proxy.newProxyInstance(RemoteSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class, RemoteStaff.class}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }
}
