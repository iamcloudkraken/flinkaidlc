import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

export default function NavBar() {
  const { isAuthenticated, logout } = useAuth();
  const navigate = useNavigate();

  function handleLogout() {
    logout();
    navigate('/login');
  }

  return (
    <nav className="bg-indigo-700 text-white shadow-md">
      <div className="container mx-auto px-4 flex items-center justify-between h-14">
        <Link
          to="/"
          className="text-lg font-semibold tracking-tight hover:text-indigo-200 transition-colors"
        >
          Flink SQL Platform
        </Link>

        {isAuthenticated && (
          <div className="flex items-center gap-6 text-sm font-medium">
            <Link
              to="/dashboard"
              className="hover:text-indigo-200 transition-colors"
            >
              Dashboard
            </Link>
            <Link
              to="/pipelines"
              className="hover:text-indigo-200 transition-colors"
            >
              Pipelines
            </Link>
            <Link
              to="/demo"
              className="bg-amber-400 hover:bg-amber-300 text-indigo-900 font-semibold px-3 py-1 rounded transition-colors"
            >
              Demo
            </Link>
            <button
              onClick={handleLogout}
              className="bg-indigo-500 hover:bg-indigo-400 px-3 py-1 rounded transition-colors"
            >
              Logout
            </button>
          </div>
        )}

        {!isAuthenticated && (
          <div className="flex items-center gap-4 text-sm font-medium">
            <Link
              to="/login"
              className="hover:text-indigo-200 transition-colors"
            >
              Login
            </Link>
            <Link
              to="/register"
              className="bg-indigo-500 hover:bg-indigo-400 px-3 py-1 rounded transition-colors"
            >
              Register
            </Link>
          </div>
        )}
      </div>
    </nav>
  );
}
