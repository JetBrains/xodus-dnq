package kotlinx.dnq.query

import kotlinx.dnq.DBTest
import kotlinx.dnq.XdModel
import kotlinx.dnq.sample.XdContact
import kotlinx.dnq.sample.XdEmail
import kotlinx.dnq.sample.XdGender
import kotlinx.dnq.sample.XdUser
import org.junit.Assert
import org.junit.Test

class AsQueryTest : DBTest() {

    override fun registerEntityTypes() {
        XdModel.registerNodes(XdUser, XdEmail, XdContact, XdGender)
    }

    @Test
    fun applyQueryToIterable() {
        val (u1, u2, u3) = transactional {
            Triple(XdUser.new {
                login = "user_one"
                contacts.add(XdEmail.new {
                    address = "user_one@example.com"
                })
            }, XdUser.new {
                login = "user_one_two"
                contacts.add(XdEmail.new {
                    address = "user_two@example.com"
                })
            }, XdUser.new {
                login = "user_one_two_three"
                contacts.add(XdEmail.new {
                    address = "user_two_three@example.com"
                })
            })
        }

        val user = transactional {
            val users = XdUser.all().asSequence().filter {
                (it.contacts.firstOrNull() as? XdEmail)?.address?.contains("@example.com") == true
            }.map { it.entity }
            users.asIterable().asQuery(XdUser).filter { it.login eq "user_one" }.firstOrNull()
        }
        Assert.assertEquals(u1, user)
    }

}
