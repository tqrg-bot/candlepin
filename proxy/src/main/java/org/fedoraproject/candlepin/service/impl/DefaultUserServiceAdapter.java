/**
 * Copyright (c) 2009 Red Hat, Inc.
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package org.fedoraproject.candlepin.service.impl;

import java.util.List;
import java.util.Set;

import org.fedoraproject.candlepin.model.Owner;
import org.fedoraproject.candlepin.model.Permission;
import org.fedoraproject.candlepin.model.User;
import org.fedoraproject.candlepin.model.UserCurator;
import org.fedoraproject.candlepin.service.UserServiceAdapter;
import org.fedoraproject.candlepin.util.Util;

import com.google.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import org.fedoraproject.candlepin.model.NewRole;

/**
 * A {@link UserServiceAdapter} implementation backed by a {@link UserCurator}
 * for user creation and persistance.
 */
public class DefaultUserServiceAdapter implements UserServiceAdapter {

    private UserCurator userCurator;
    
    @Inject
    public DefaultUserServiceAdapter(UserCurator userCurator) {
        this.userCurator = userCurator;
    }
    
    @Override
    public User createUser(User user) {
        return this.userCurator.create(user);
    }

    @Override
    public List<NewRole> getRoles(String username) {
        User user = this.userCurator.findByLogin(username);

        if (user != null) {
            return new ArrayList<NewRole>(user.getRoles());
        }

        return Collections.emptyList();
    }
    
    @Override
    public boolean validateUser(String username, String password) {
        User user = this.userCurator.findByLogin(username);
        String hashedPassword = Util.hash(password);
        
        if (user != null && password != null && hashedPassword != null) {
            return hashedPassword.equals(user.getHashedPassword());
        }
        
        return false;
    }

    @Override
    public boolean isReadyOnly() {
        return false;
    }

    @Override
    public void deleteUser(User user) {
        userCurator.delete(user);
    }

    @Override
    public List<User> listByOwner(Owner owner) {
        return userCurator.findByOwner(owner);
    }

    @Override
    public User findByLogin(String login) {
        return userCurator.findByLogin(login);
    }

}
