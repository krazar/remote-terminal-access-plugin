package org.jenkinsci.plugins.remote_terminal_access.ssh;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.filterchain.IoFilterAdapter;
import org.apache.mina.core.session.DummySession;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.WriteRequest;
import org.apache.sshd.common.Factory;
import org.apache.sshd.common.future.CloseFuture;
import org.apache.sshd.server.Command;
import org.apache.sshd.server.CommandFactory;
import org.apache.sshd.server.FileSystemView;
import org.apache.sshd.server.ServerFactoryManager;
import org.apache.sshd.server.session.ServerSession;
import org.jenkinsci.main.modules.sshd.AsynchronousCommand;
import org.jenkinsci.main.modules.sshd.SshCommandFactory.CommandLine;

import java.util.HashMap;
import java.util.Map;

/**
 * SSH command that tunnels another SSH session for the purpose of adding contextual data around it.
 *
 * <p>
 * To implement a subtype,
 *
 * <ul>
 * <li>Override the {@link #run()} method and parse command line options and arguments
 *     that define the context.
 * <li>Implement {@link #getCommandFactory()} and {@link #getShellFactory()} to
 *     run a command and shell for the tunneled SSH sessions. This is where you refer
 *     to the parsed context information.
 * </ul>
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class AbstractTunnelCommand extends AsynchronousCommand {
    public AbstractTunnelCommand(CommandLine cmdLine) {
        super(cmdLine);
    }

    /**
     * Turn the connection over into another nested SSH session.
     * IOW, stdin/stdout behave as if it's freshly connected to
     * another SSH port.
     */
    @Override
    protected int run() throws Exception {
        Map<String,Object> overrides = new HashMap<String, Object>();
        overrides.put("getCommandFactory",getCommandFactory());
        overrides.put("getShellFactory",getShellFactory());
        overrides.put("getFileSystemFactory",getFileSystemFactory());

        ServerFactoryManager sfm = InterceptingProxy.create(ServerFactoryManager.class,
                getSession().getServerFactoryManager(),
                overrides);

        return pump(sfm);
    }

    protected abstract CommandFactory getCommandFactory();
    protected abstract Factory<Command> getShellFactory();

    /**
     * Override this method to add sftp support
     */
    protected FileSystemView getFileSystemFactory() {
        // default is to delegate, but you can add more
        return null;
    }

    /**
     * Pumps data between the stdin/out and the tunneled SSH session.
     */
    private int pump(ServerFactoryManager sfm) throws Exception {
        // create a nested session that handles the tunneled SSH protocol.
        IoSession ios = new DummySession();
        ios.setAttribute("tunnel", getCmdLine());
        ios.getFilterChain().addFirst("writer",new IoFilterAdapter() {
            private final byte[] ary = new byte[2048];
            @Override
            public void filterWrite(NextFilter nextFilter, IoSession session, WriteRequest writeRequest) throws Exception {
                IoBuffer buf = (IoBuffer)writeRequest.getMessage();

                int r;
                while ((r=buf.remaining())>0) {
                    int chunk = Math.min(r,ary.length);
                    buf.get(ary, 0, chunk);
                    getOutputStream().write(ary,0,chunk);
                }
                getOutputStream().flush();
            }
        });

        ServerSession target = new ServerSession(sfm,ios);

        // TODO: do it like async I/O
        // a Command is owned by ChannelSession
        byte[] buf = new byte[2048];
        IoBuffer iob = IoBuffer.wrap(buf);
        int len;

        // this would be triggered from ChannelSession.doWriteData if we are doing this async
        while ((len=getInputStream().read(buf))>=0) {
            // it's unclear if the caller is responsible for sending in new buffer object
            // but looking at AbstractSession.messageReceived, this should work
            iob.clear();
            iob.limit(len);
            target.messageReceived(iob);
        }

        // this would be triggered from ChannelSession.doClose if we are doing this async
        CloseFuture close = target.close(true);// for async I can add listener
        close.await();

        return 0;
    }

    /*
        Async support for Command

        ChannelSession would define an interface for handling inbound data
        (one for data, the other for "extended data"

        protected void data(byte[] data, int off, int len) throws IOException;
        protected void eof() throws IOException;

        and in return there needs to be flow control mechanism.
        maybe just expose sendWindowAdjust()
        ChannelSession.shellIn should be behind this abstraction
     */
}
