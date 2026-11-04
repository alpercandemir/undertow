class Subject { void io(Runnable request) { try(var e=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) { e.submit(request); } } }
